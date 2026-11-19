package com.jamilxt.k8sconfigmap.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import com.jamilxt.k8sconfigmap.model.UsageKind
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Scans YAML configuration files:
 * - Spring / framework config files (`application*.yml`, `bootstrap*.yml`): both `${...}` placeholders and direct property overrides.
 * - Any other non-ConfigMap YAML file: `${...}` placeholders.
 */
class YamlConfigUsageScanner : UsageScanner {

    override fun collectUsages(
        project: Project,
        externalRoots: List<VirtualFile>,
        settings: NavigatorSettings,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit
    ) {
        val scope = GlobalSearchScope.projectScope(project)
        val yamlFiles = LinkedHashSet<VirtualFile>()
        yamlFiles.addAll(FilenameIndex.getAllFilesByExt(project, "yml", scope))
        yamlFiles.addAll(FilenameIndex.getAllFilesByExt(project, "yaml", scope))

        UsageIndexService.collectExternalFiles(
            externalRoots,
            predicate = { vf -> val ext = vf.extension?.lowercase(); ext == "yml" || ext == "yaml" },
            sink = yamlFiles
        )

        val psiManager = PsiManager.getInstance(project)
        for (vf in yamlFiles) {
            if (!vf.isValid || vf.isDirectory) continue
            val yamlFile = psiManager.findFile(vf) as? YAMLFile ?: continue
            val isAppConfig = isAppConfigFile(vf.name)
            for (doc in yamlFile.documents) {
                if (isConfigMapDocument(doc)) continue
                val root = doc.topLevelValue as? YAMLMapping ?: continue
                walkYamlMapping(root, "", vf.name, isAppConfig, settings, pointerManager, sink)
            }
        }
    }

    override fun extractKeysAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): List<String> {
        val kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return emptyList()
        val cmIndex = ConfigMapIndexService.getInstance(element.project)
        if (cmIndex.isConfigMapDataKey(kv)) return emptyList()

        val doc = PsiTreeUtil.getParentOfType(kv, YAMLDocument::class.java, false)
        if (doc != null && isConfigMapDocument(doc)) return emptyList()

        val keys = mutableListOf<String>()
        val scalar = kv.value as? YAMLScalar
        if (scalar != null) {
            keys.addAll(PropertyKeyNormalizer.extractPlaceholders(scalar.textValue))
        }
        val fileName = kv.containingFile?.name.orEmpty()
        if (settings.matchApplicationPropertyOverrides && isAppConfigFile(fileName) && kv.value !is YAMLMapping) {
            val fullPath = computeYamlPropertyPath(kv)
            if (fullPath.isNotEmpty()) keys.add(fullPath)
        }
        return keys.distinct()
    }

    private fun walkYamlMapping(
        mapping: YAMLMapping,
        prefix: String,
        fileName: String,
        isAppConfig: Boolean,
        settings: NavigatorSettings,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit
    ) {
        for (kv in mapping.keyValues) {
            val key = kv.keyText.trim()
            if (key.isEmpty()) continue
            val fullPath = if (prefix.isEmpty()) key else "$prefix.$key"
            val value = kv.value

            if (value is YAMLMapping) {
                walkYamlMapping(value, fullPath, fileName, isAppConfig, settings, pointerManager, sink)
            } else {
                if (value is YAMLScalar) {
                    for (ph in PropertyKeyNormalizer.extractPlaceholders(value.textValue)) {
                        val canonical = PropertyKeyNormalizer.canonicalize(ph)
                        if (canonical.isNotEmpty()) {
                            sink(
                                ProjectUsage(
                                    rawKey = ph,
                                    canonicalKey = canonical,
                                    usageKind = UsageKind.CONFIG_PLACEHOLDER,
                                    displayLocation = "$fileName ($fullPath)",
                                    psiPointer = pointerManager.createSmartPsiElementPointer(value)
                                )
                            )
                        }
                    }
                }
                if (isAppConfig && settings.matchApplicationPropertyOverrides) {
                    val canonical = PropertyKeyNormalizer.canonicalize(fullPath)
                    if (canonical.isNotEmpty()) {
                        sink(
                            ProjectUsage(
                                rawKey = fullPath,
                                canonicalKey = canonical,
                                usageKind = UsageKind.CONFIG_OVERRIDE,
                                displayLocation = "$fileName ($fullPath)",
                                psiPointer = pointerManager.createSmartPsiElementPointer(kv)
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun isAppConfigFile(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return (lower.startsWith("application") || lower.startsWith("bootstrap")) &&
                (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties"))
        }

        fun isConfigMapDocument(doc: YAMLDocument): Boolean {
            val root = doc.topLevelValue as? YAMLMapping ?: return false
            return root.getKeyValueByKey("kind")?.valueText?.trim() == "ConfigMap"
        }

        fun computeYamlPropertyPath(keyValue: YAMLKeyValue): String {
            val parts = ArrayDeque<String>()
            var current: PsiElement? = keyValue
            while (current != null && current !is YAMLFile) {
                if (current is YAMLKeyValue) {
                    val key = current.keyText.trim()
                    if (key.isNotEmpty()) parts.addFirst(key)
                }
                current = current.parent
            }
            return parts.joinToString(".")
        }
    }
}
