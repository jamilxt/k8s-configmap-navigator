package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.icons.PluginIcons
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
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
        if (usageByElement.isEmpty()) return

        val targets = usageByElement.keys.toList()
        val tooltip = if (targets.size == 1) {
            val u = usageByElement.values.first()
            "Navigate to usage (${u.usageKind.label}: ${u.displayLocation})"
        } else {
            "Navigate to ${targets.size} usages of '$key'"
        }

        val marker = NavigationGutterIconBuilder.create(PluginIcons.Spring)
            .setTargets(targets)
            .setTooltipText(tooltip)
            .setPopupTitle("Usages of '$key'")
            .setCellRenderer { ProjectUsageCellRenderer(usageByElement) }
            .createLineMarkerInfo(keyLeaf)

        result.add(marker)
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
