package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.jamilxt.k8sconfigmap.actions.AddToConfigMapAction
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.icons.PluginIcons
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.model.ConfigMapEntry
import javax.swing.Icon

object ConfigMapMarkerHelper {

    fun buildMarkerForKeys(
        leaf: PsiElement,
        keys: List<String>,
        allowAddMarker: Boolean = false,
        defaultValue: String? = null
    ): RelatedItemLineMarkerInfo<PsiElement>? {
        val distinctKeys = keys.distinct()
        if (distinctKeys.isEmpty()) return null
        val cmIndex = ConfigMapIndexService.getInstance(leaf.project)
        val entryMap = linkedMapOf<PsiElement, ConfigMapEntry>()
        for (key in distinctKeys) {
            for (entry in cmIndex.findEntriesByKey(key)) {
                val target = entry.psiPointer.element ?: continue
                entryMap.putIfAbsent(target, entry)
            }
        }
        if (entryMap.isNotEmpty()) {
            return buildConfigMapMarker(leaf, entryMap)
        }
        if (allowAddMarker && cmIndex.getAllConfigMaps().isNotEmpty()) {
            return buildAddToConfigMapMarker(leaf, distinctKeys, defaultValue)
        }
        return null
    }

    fun buildAddToConfigMapMarker(
        leaf: PsiElement,
        keys: List<String>,
        defaultValue: String? = null
    ): RelatedItemLineMarkerInfo<PsiElement> {
        val tooltip = if (keys.size == 1) {
            "Add '${PropertyKeyNormalizer.toEnvVarKey(keys.first())}' to K8s ConfigMap..."
        } else {
            "Add property to K8s ConfigMap..."
        }
        val handler = GutterIconNavigationHandler<PsiElement> { _, elt ->
            AddToConfigMapAction.showAddDialog(elt.project, keys, defaultValue)
        }
        return RelatedItemLineMarkerInfo(
            leaf,
            leaf.textRange,
            PluginIcons.ConfigMapAdd,
            { tooltip },
            handler,
            GutterIconRenderer.Alignment.LEFT
        ) { emptyList() }
    }

    fun buildConfigMapMarker(
        leaf: PsiElement,
        entryMap: Map<PsiElement, ConfigMapEntry>
    ): RelatedItemLineMarkerInfo<PsiElement> {
        val targets = entryMap.keys.toList()
        val tooltip = if (targets.size == 1) {
            val e = entryMap.values.first()
            "Navigate to K8s ConfigMap: ${e.displayName()} = ${e.valuePreview}"
        } else {
            "Navigate to ${targets.size} K8s ConfigMap definitions"
        }

        return NavigationGutterIconBuilder.create(PluginIcons.ConfigMap)
            .setTargets(targets)
            .setTooltipText(tooltip)
            .setPopupTitle("Kubernetes ConfigMap Definitions")
            .setCellRenderer { ConfigMapEntryCellRenderer(entryMap) }
            .createLineMarkerInfo(leaf)
    }

    class ConfigMapEntryCellRenderer(
        private val entryMap: Map<PsiElement, ConfigMapEntry>
    ) : PsiElementListCellRenderer<PsiElement>() {

        override fun getElementText(element: PsiElement): String {
            val entry = entryMap[element]
            return entry?.displayName() ?: element.text.take(40)
        }

        override fun getContainerText(element: PsiElement, name: String?): String {
            val entry = entryMap[element] ?: return ""
            val valuePart = if (entry.valuePreview.isNotEmpty()) "= ${entry.valuePreview}  " else ""
            return "$valuePart(${entry.virtualFile.name})"
        }

        override fun getIcon(element: PsiElement): Icon = PluginIcons.ConfigMap
    }
}
