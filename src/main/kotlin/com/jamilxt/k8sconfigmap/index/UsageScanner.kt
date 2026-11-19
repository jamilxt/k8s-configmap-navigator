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
     * Extracts candidate ConfigMap keys referenced at [element] for reverse navigation (Code/Config -> ConfigMap).
     */
    fun extractKeysAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): List<String> = emptyList()

    companion object {
        val EP_NAME: ExtensionPointName<UsageScanner> =
            ExtensionPointName.create("com.jamilxt.k8s-configmap-navigator.usageScanner")
    }
}
