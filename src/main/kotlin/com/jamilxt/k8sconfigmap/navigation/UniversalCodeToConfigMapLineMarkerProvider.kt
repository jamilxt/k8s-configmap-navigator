package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jamilxt.k8sconfigmap.core.UniversalCodePatterns
import com.jamilxt.k8sconfigmap.index.UniversalCodeUsageScanner
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings

/**
 * Universal gutter icon provider for JS/TS, Python, Go, C#/.NET, Rust, PHP, Ruby, .env, TOML, and JSON files.
 * Works in any JetBrains IDE without requiring language-specific plugin dependencies.
 */
class UniversalCodeToConfigMapLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        // Trigger once per file on the root PsiFile to avoid redundant regex work on every token,
        // while still attaching each marker strictly to a leaf PsiElement.
        val psiFile = element as? PsiFile ?: return
        val vf = psiFile.virtualFile ?: return
        val settings = NavigatorSettings.getInstance(psiFile.project)
        if (!UniversalCodeUsageScanner.isSupportedFile(vf, settings.matchSystemGetEnv)) return

        val text = psiFile.text
        if (text.isEmpty()) return

        val matches = when {
            UniversalCodePatterns.isDotEnvFile(vf.name) -> UniversalCodePatterns.findDotEnvMatches(text)
            vf.extension?.lowercase() in UniversalCodePatterns.SUPPORTED_CONFIG_EXTENSIONS ->
                UniversalCodePatterns.findPlaceholderMatches(text)
            else -> UniversalCodePatterns.findCodeMatches(text)
        }
        if (matches.isEmpty()) return

        val markedLeaves = HashSet<PsiElement>()
        for (match in matches) {
            val leaf = psiFile.findElementAt(match.keyStartOffset) ?: continue
            if (!markedLeaves.add(leaf)) continue
            ConfigMapMarkerHelper.buildMarkerForKeys(leaf, listOf(match.key))?.let { result.add(it) }
        }
    }
}
