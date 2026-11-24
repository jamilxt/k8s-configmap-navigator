package com.jamilxt.k8sconfigmap.index

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings

/**
 * Extension point for language- and framework-specific usage scanners.
 * Optional plugins (Java/Kotlin UAST, Properties) register scanners via optional config files,
 * allowing the core plugin to run in any JetBrains IDE (IDEA, PyCharm, GoLand, WebStorm, Rider, etc.).
 */
interface UsageScanner {
    fun collectUsages(
        project: Project,
        externalRoots: List<VirtualFile>,
        settings: NavigatorSettings,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit
    )

    /**
     * Extracts candidate ConfigMap keys referenced at [element] for context actions (e.g. "Add to K8s ConfigMap")
     * and reverse navigation (Code/Config -> ConfigMap).
     */
    fun extractKeysAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): List<String> = emptyList()

    /**
     * Extracts candidate ConfigMap keys at [element] specifically for `Ctrl+Click` / Go to Declaration navigation.
     * Defaults to [extractKeysAtElement]. Language scanners (such as JVM UAST) override this to avoid intercepting
     * IDE declaration/usages navigation on language declarations (fields, parameters, methods, types).
     */
    fun extractKeysForGotoDeclaration(element: PsiElement, offset: Int, settings: NavigatorSettings): List<String> =
        extractKeysAtElement(element, offset, settings)

    /**
     * Optionally extracts a default value associated with the config variable at [element]
     * (e.g., `@Value("${key:default}")` or a field literal initializer).
     */
    fun extractDefaultValueAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): String? = null

    companion object {
        val EP_NAME: ExtensionPointName<UsageScanner> =
            ExtensionPointName.create("com.jamilxt.k8s-configmap-navigator.usageScanner")
    }
}
