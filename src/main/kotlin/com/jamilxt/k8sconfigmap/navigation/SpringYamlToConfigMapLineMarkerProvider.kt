package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.YamlConfigUsageScanner
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

class SpringYamlToConfigMapLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val keyValue = element as? YAMLKeyValue ?: return
        if (keyValue.value is YAMLMapping) return
        val project = keyValue.project
        if (ConfigMapIndexService.getInstance(project).isConfigMapDataKey(keyValue)) return

        val doc = PsiTreeUtil.getParentOfType(keyValue, YAMLDocument::class.java, false)
        if (doc != null && YamlConfigUsageScanner.isConfigMapDocument(doc)) return

        val keyLeaf = keyValue.key?.let { PsiTreeUtil.getDeepestFirst(it) } ?: return
        val settings = NavigatorSettings.getInstance(project)

        val keys = mutableListOf<String>()
        val scalar = keyValue.value as? YAMLScalar
        if (scalar != null) {
            keys.addAll(PropertyKeyNormalizer.extractPlaceholders(scalar.textValue))
        }
        val fileName = keyValue.containingFile?.name.orEmpty()
        if (settings.matchApplicationPropertyOverrides && YamlConfigUsageScanner.isAppConfigFile(fileName)) {
            val fullPath = YamlConfigUsageScanner.computeYamlPropertyPath(keyValue)
            if (fullPath.isNotEmpty()) keys.add(fullPath)
        }
        if (keys.isEmpty()) return

        ConfigMapMarkerHelper.buildMarkerForKeys(keyLeaf, keys)?.let { result.add(it) }
    }
}
