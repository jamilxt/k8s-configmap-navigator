package com.jamilxt.k8sconfigmap.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class UsageIndexService(private val project: Project) {

    private fun getIndex(): Map<String, List<ProjectUsage>> {
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

    fun findUsagesForConfigMapKey(configMapKey: String): List<ProjectUsage> {
        val settings = NavigatorSettings.getInstance(project)
        val canonical = PropertyKeyNormalizer.canonicalize(configMapKey)
        if (canonical.isEmpty()) return emptyList()
        val candidates = getIndex()[canonical].orEmpty()
        return if (settings.enableRelaxedBinding) {
            candidates.filter { PropertyKeyNormalizer.matches(configMapKey, it.rawKey) }
        } else {
            candidates.filter { it.rawKey.equals(configMapKey, ignoreCase = false) }
        }
    }

    fun extractKeysAtElement(element: PsiElement, offset: Int): List<String> {
        val settings = NavigatorSettings.getInstance(project)
        val keys = LinkedHashSet<String>()
        for (scanner in UsageScanner.EP_NAME.extensionList) {
            keys.addAll(scanner.extractKeysAtElement(element, offset, settings))
        }
        return keys.toList()
    }

    fun extractDefaultValueAtElement(element: PsiElement, offset: Int): String? {
        val settings = NavigatorSettings.getInstance(project)
        for (scanner in UsageScanner.EP_NAME.extensionList) {
            val value = scanner.extractDefaultValueAtElement(element, offset, settings)
            if (value != null) return value
        }
        return null
    }

    private fun buildIndex(settings: NavigatorSettings): Map<String, List<ProjectUsage>> {
        val externalRoots = settings.externalProjectPaths.mapNotNull { pathStr ->
            try {
                LocalFileSystem.getInstance().findFileByNioFile(Path.of(pathStr))
            } catch (_: InvalidPathException) {
                null
            }
        }

        val pointerManager = SmartPointerManager.getInstance(project)
        val index = mutableMapOf<String, MutableList<ProjectUsage>>()

        for (scanner in UsageScanner.EP_NAME.extensionList) {
            scanner.collectUsages(project, externalRoots, settings, pointerManager) { usage ->
                index.computeIfAbsent(usage.canonicalKey) { mutableListOf() }.add(usage)
            }
        }

        return index
    }

    companion object {
        val IGNORED_DIRS = setOf(".git", ".idea", "node_modules", "build", "target", "out", ".gradle", "dist", "venv", ".venv", "__pycache__")

        fun getInstance(project: Project): UsageIndexService = project.service()

        fun collectExternalFiles(
            roots: List<VirtualFile>,
            predicate: (VirtualFile) -> Boolean,
            sink: MutableSet<VirtualFile>
        ) {
            for (root in roots) {
                if (!root.isValid) continue
                VfsUtilCore.iterateChildrenRecursively(
                    root,
                    { vf -> !vf.isDirectory || !IGNORED_DIRS.contains(vf.name) },
                    { vf ->
                        if (!vf.isDirectory && predicate(vf)) {
                            sink.add(vf)
                        }
                        true
                    }
                )
            }
        }
    }
}
