package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.icons.PluginIcons
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.FrameworkPropertyIndexService
import com.jamilxt.k8sconfigmap.index.FrameworkPropertyMatch
import com.jamilxt.k8sconfigmap.index.PropertyOrigin
import com.jamilxt.k8sconfigmap.index.UsageIndexService
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import org.jetbrains.yaml.psi.YAMLKeyValue
import javax.swing.Icon

class ConfigMapToSpringLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val keyValue = element as? YAMLKeyValue ?: return
        val keyLeaf = keyValue.key?.let { PsiTreeUtil.getDeepestFirst(it) } ?: return
        val project = keyValue.project
        val cmIndex = ConfigMapIndexService.getInstance(project)
        if (!cmIndex.isConfigMapDataKey(keyValue)) return

        val key = keyValue.keyText.trim()
        if (key.isEmpty()) return

        val usages = UsageIndexService.getInstance(project).findUsagesForConfigMapKey(key)
        val usageByElement = linkedMapOf<PsiElement, ProjectUsage>()
        for (usage in usages) {
            val target = usage.psiPointer.element ?: continue
            usageByElement.putIfAbsent(target, usage)
        }
        val frameworkMatch = FrameworkPropertyIndexService.getInstance(project).findFrameworkProperty(key)

        if (usageByElement.isEmpty()) {
            if (frameworkMatch != null) {
                val icon = when (frameworkMatch.origin) {
                    PropertyOrigin.FRAMEWORK -> PluginIcons.Framework
                    PropertyOrigin.LIBRARY -> PluginIcons.Library
                }
                val tooltip = buildFrameworkTooltip(frameworkMatch, usageSummary = null)
                result.add(
                    RelatedItemLineMarkerInfo(
                        keyLeaf,
                        keyLeaf.textRange,
                        icon,
                        { tooltip },
                        null,
                        GutterIconRenderer.Alignment.LEFT
                    ) { emptyList() }
                )
            }
            return
        }

        val targets = usageByElement.keys.toList()
        val usageSummary = if (targets.size == 1) {
            val u = usageByElement.values.first()
            "Navigate to usage (${u.usageKind.label}: ${u.displayLocation})"
        } else {
            "Navigate to ${targets.size} usages of '$key'"
        }

        val icon = when (frameworkMatch?.origin) {
            PropertyOrigin.FRAMEWORK -> PluginIcons.FrameworkUsed
            PropertyOrigin.LIBRARY -> PluginIcons.LibraryUsed
            null -> PluginIcons.Spring
        }
        val tooltip = if (frameworkMatch != null) {
            buildFrameworkTooltip(frameworkMatch, usageSummary)
        } else {
            usageSummary
        }

        val marker = NavigationGutterIconBuilder.create(icon)
            .setTargets(targets)
            .setTooltipText(tooltip)
            .setPopupTitle("Usages of '$key'")
            .setCellRenderer { ProjectUsageCellRenderer(usageByElement) }
            .createLineMarkerInfo(keyLeaf)

        result.add(marker)
    }

    private fun buildFrameworkTooltip(match: FrameworkPropertyMatch, usageSummary: String?): String = buildString {
        val originPrefix = when (match.origin) {
            PropertyOrigin.FRAMEWORK -> "${match.frameworkName} property"
            PropertyOrigin.LIBRARY -> "Library (${match.frameworkName}) property"
        }
        append("$originPrefix: ${match.propertyName}")
        if (!match.description.isNullOrBlank()) {
            append("\n${match.description}")
        }
        if (!match.defaultValue.isNullOrBlank()) {
            append("\nDefault: ${match.defaultValue}")
        }
        if (usageSummary != null) {
            append("\n$usageSummary")
        }
    }

    private class ProjectUsageCellRenderer(
        private val usageMap: Map<PsiElement, ProjectUsage>
    ) : PsiElementListCellRenderer<PsiElement>() {

        override fun getElementText(element: PsiElement): String {
            val usage = usageMap[element]
            return if (usage != null) {
                "${usage.displayLocation} [${usage.usageKind.label}]"
            } else {
                element.containingFile?.name ?: element.text.take(40)
            }
        }

        override fun getContainerText(element: PsiElement, name: String?): String {
            val usage = usageMap[element]
            return usage?.rawKey ?: element.containingFile?.name.orEmpty()
        }

        override fun getIcon(element: PsiElement): Icon = PluginIcons.Spring
    }
}
