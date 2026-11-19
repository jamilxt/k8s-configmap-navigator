package com.jamilxt.k8sconfigmap.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.core.UniversalCodePatterns
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings

/**
 * Language-agnostic scanner supporting Node.js/TypeScript, Python, Go, C#/.NET, Rust, PHP, Ruby,
 * .env files, and TOML/JSON placeholder files across any JetBrains IDE.
 */
class UniversalCodeUsageScanner : UsageScanner {

    override fun collectUsages(
        project: Project,
        externalRoots: List<VirtualFile>,
        settings: NavigatorSettings,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit
    ) {
        val scope = GlobalSearchScope.projectScope(project)
        val files = LinkedHashSet<VirtualFile>()

        if (settings.matchSystemGetEnv) {
            for (ext in UniversalCodePatterns.SUPPORTED_CODE_EXTENSIONS) {
                files.addAll(FilenameIndex.getAllFilesByExt(project, ext, scope))
            }
        }
        for (ext in UniversalCodePatterns.SUPPORTED_CONFIG_EXTENSIONS) {
            files.addAll(FilenameIndex.getAllFilesByExt(project, ext, scope))
        }
        // Collect .env* files in project
        FilenameIndex.processAllFileNames({ name ->
            if (UniversalCodePatterns.isDotEnvFile(name)) {
                files.addAll(FilenameIndex.getVirtualFilesByName(name, scope))
            }
            true
        }, scope, null)

        UsageIndexService.collectExternalFiles(
            externalRoots,
            predicate = { vf -> isSupportedFile(vf, settings.matchSystemGetEnv) },
            sink = files
        )

        val psiManager = PsiManager.getInstance(project)
        for (vf in files) {
            if (!vf.isValid || vf.isDirectory) continue
            val psiFile = psiManager.findFile(vf) ?: continue
            val text = psiFile.text
            if (text.isEmpty()) continue

            val matches = when {
                UniversalCodePatterns.isDotEnvFile(vf.name) -> UniversalCodePatterns.findDotEnvMatches(text)
                vf.extension?.lowercase() in UniversalCodePatterns.SUPPORTED_CONFIG_EXTENSIONS ->
                    UniversalCodePatterns.findPlaceholderMatches(text)
                else -> UniversalCodePatterns.findCodeMatches(text)
            }

            for (match in matches) {
                val canonical = PropertyKeyNormalizer.canonicalize(match.key)
                if (canonical.isEmpty()) continue
                val anchor = psiFile.findElementAt(match.keyStartOffset) ?: psiFile
                sink(
                    ProjectUsage(
                        rawKey = match.key,
                        canonicalKey = canonical,
                        usageKind = match.kind,
                        displayLocation = vf.name,
                        psiPointer = pointerManager.createSmartPsiElementPointer(anchor)
                    )
                )
            }
        }
    }

    override fun extractKeysAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): List<String> {
        val psiFile = element.containingFile ?: return emptyList()
        val vf = psiFile.virtualFile ?: return emptyList()
        if (!isSupportedFile(vf, settings.matchSystemGetEnv)) return emptyList()

        val fullText = psiFile.text
        val targetOffset = if (offset in 0 until fullText.length) offset else (element.textRange?.startOffset ?: return emptyList())
        if (targetOffset < 0 || targetOffset >= fullText.length) return emptyList()

        // Extract the single line containing the caret offset for fast O(line length) pattern matching
        val lineStart = fullText.lastIndexOf('\n', (targetOffset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = fullText.indexOf('\n', targetOffset).let { if (it < 0) fullText.length else it }
        val lineText = fullText.substring(lineStart, lineEnd)
        val localOffset = targetOffset - lineStart

        val matches = when {
            UniversalCodePatterns.isDotEnvFile(vf.name) -> UniversalCodePatterns.findDotEnvMatches(lineText)
            vf.extension?.lowercase() in UniversalCodePatterns.SUPPORTED_CONFIG_EXTENSIONS ->
                UniversalCodePatterns.findPlaceholderMatches(lineText)
            else -> UniversalCodePatterns.findCodeMatches(lineText)
        }

        return matches
            .filter { localOffset in (it.keyStartOffset - 1)..(it.keyEndOffset + 1) }
            .map { it.key }
            .distinct()
    }

    companion object {
        fun isSupportedFile(vf: VirtualFile, includeCode: Boolean): Boolean {
            if (UniversalCodePatterns.isDotEnvFile(vf.name)) return true
            val ext = vf.extension?.lowercase() ?: return false
            if (ext in UniversalCodePatterns.SUPPORTED_CONFIG_EXTENSIONS) return true
            return includeCode && ext in UniversalCodePatterns.SUPPORTED_CODE_EXTENSIONS
        }
    }
}
