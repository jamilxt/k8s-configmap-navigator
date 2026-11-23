package com.jamilxt.k8sconfigmap.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ConfigMapEntry
import com.jamilxt.k8sconfigmap.model.ConfigMapTarget
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ConfigMapIndexService(private val project: Project) {

    private data class IndexData(
        val entriesByCanonical: Map<String, List<ConfigMapEntry>>,
        val configMaps: List<ConfigMapTarget>
    )

    private val ignoredDirs = setOf(".git", ".idea", "node_modules", "build", "target", "out", ".gradle")

    private fun getIndexData(): IndexData {
        val settings = NavigatorSettings.getInstance(project)
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val result = buildIndex(settings)
            CachedValueProvider.Result.create(
                result,
                PsiModificationTracker.MODIFICATION_COUNT,
                settings.modificationTracker
            )
        }
    }

    fun findEntriesByKey(rawKey: String): List<ConfigMapEntry> {
        val settings = NavigatorSettings.getInstance(project)
        val canonical = PropertyKeyNormalizer.canonicalize(rawKey)
        if (canonical.isEmpty()) return emptyList()
        val candidates = getIndexData().entriesByCanonical[canonical].orEmpty()
        return if (settings.enableRelaxedBinding) {
            candidates.filter { PropertyKeyNormalizer.matches(it.key, rawKey) }
        } else {
            candidates.filter { it.key == rawKey }
        }
    }

    fun getAllEntries(): List<ConfigMapEntry> = getIndexData().entriesByCanonical.values.flatten()

    fun getAllConfigMaps(): List<ConfigMapTarget> = getIndexData().configMaps

    fun isConfigMapDataKey(keyValue: YAMLKeyValue): Boolean {
        val dataMapping = keyValue.parent as? YAMLMapping ?: return false
        val dataKv = dataMapping.parent as? YAMLKeyValue ?: return false
        if (dataKv.keyText != "data") return false
        val rootMapping = dataKv.parent as? YAMLMapping ?: return false
        if (rootMapping.parent !is YAMLDocument) return false
        val kind = rootMapping.getKeyValueByKey("kind")?.valueText?.trim()
        return kind == "ConfigMap"
    }

    private fun buildIndex(settings: NavigatorSettings): IndexData {
        val yamlFiles = LinkedHashSet<VirtualFile>()
        val scope = GlobalSearchScope.projectScope(project)
        yamlFiles.addAll(FilenameIndex.getAllFilesByExt(project, "yaml", scope))
        yamlFiles.addAll(FilenameIndex.getAllFilesByExt(project, "yml", scope))

        for (externalPath in settings.externalConfigMapPaths) {
            collectExternalYamlFiles(externalPath, yamlFiles)
        }

        val psiManager = PsiManager.getInstance(project)
        val pointerManager = SmartPointerManager.getInstance(project)
        val index = mutableMapOf<String, MutableList<ConfigMapEntry>>()
        val configMaps = mutableListOf<ConfigMapTarget>()

        for (vFile in yamlFiles) {
            if (!vFile.isValid || vFile.isDirectory) continue
            val psiFile = psiManager.findFile(vFile) as? YAMLFile ?: continue
            parseConfigMapFile(psiFile, vFile, pointerManager, index, configMaps)
        }

        return IndexData(index, configMaps)
    }

    private fun collectExternalYamlFiles(pathStr: String, sink: MutableSet<VirtualFile>) {
        val nioPath = try {
            Path.of(pathStr)
        } catch (_: InvalidPathException) {
            return
        }
        val root = LocalFileSystem.getInstance().findFileByNioFile(nioPath) ?: return
        if (!root.isDirectory) {
            if (isYamlFile(root)) sink.add(root)
            return
        }
        VfsUtilCore.iterateChildrenRecursively(
            root,
            { vf -> !vf.isDirectory || !ignoredDirs.contains(vf.name) },
            { vf ->
                if (!vf.isDirectory && isYamlFile(vf)) {
                    sink.add(vf)
                }
                true
            }
        )
    }

    private fun isYamlFile(vf: VirtualFile): Boolean {
        val ext = vf.extension?.lowercase()
        return ext == "yaml" || ext == "yml"
    }

    private fun parseConfigMapFile(
        yamlFile: YAMLFile,
        vFile: VirtualFile,
        pointerManager: SmartPointerManager,
        index: MutableMap<String, MutableList<ConfigMapEntry>>,
        configMaps: MutableList<ConfigMapTarget>
    ) {
        for (doc in yamlFile.documents) {
            val rootMapping = doc.topLevelValue as? YAMLMapping ?: continue
            val kind = rootMapping.getKeyValueByKey("kind")?.valueText?.trim()
            if (kind != "ConfigMap") continue

            val metadata = rootMapping.getKeyValueByKey("metadata")?.value as? YAMLMapping
            val cmName = metadata?.getKeyValueByKey("name")?.valueText?.trim().orEmpty().ifEmpty { vFile.nameWithoutExtension }
            val namespace = metadata?.getKeyValueByKey("namespace")?.valueText?.trim()?.takeIf { it.isNotEmpty() }

            val existingKeys = linkedMapOf<String, String>()
            val dataMapping = rootMapping.getKeyValueByKey("data")?.value as? YAMLMapping
            if (dataMapping != null) {
                for (kv in dataMapping.keyValues) {
                    val key = kv.keyText.trim()
                    if (key.isEmpty()) continue
                    val canonical = PropertyKeyNormalizer.canonicalize(key)
                    if (canonical.isEmpty()) continue

                    val rawValue = kv.valueText.trim().replace("\n", " ")
                    val preview = if (rawValue.length > 60) rawValue.take(57) + "..." else rawValue
                    existingKeys[key] = preview

                    val entry = ConfigMapEntry(
                        key = key,
                        canonicalKey = canonical,
                        valuePreview = preview,
                        configMapName = cmName,
                        namespace = namespace,
                        virtualFile = vFile,
                        psiPointer = pointerManager.createSmartPsiElementPointer(kv)
                    )
                    index.computeIfAbsent(canonical) { mutableListOf() }.add(entry)
                }
            }

            configMaps.add(
                ConfigMapTarget(
                    name = cmName,
                    namespace = namespace,
                    virtualFile = vFile,
                    documentPointer = pointerManager.createSmartPsiElementPointer(doc),
                    existingKeys = existingKeys
                )
            )
        }
    }

    companion object {
        fun getInstance(project: Project): ConfigMapIndexService = project.service()
    }
}
