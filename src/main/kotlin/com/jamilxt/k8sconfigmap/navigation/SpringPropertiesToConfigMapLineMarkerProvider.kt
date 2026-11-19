package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.lang.properties.psi.Property
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.index.PropertiesUsageScanner
import com.jamilxt.k8sconfigmap.index.YamlConfigUsageScanner
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings

class SpringPropertiesToConfigMapLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val property = element as? Property ?: return
        val fileName = property.containingFile?.name ?: return
        val settings = NavigatorSettings.getInstance(property.project)
        val includeKey = settings.matchApplicationPropertyOverrides && YamlConfigUsageScanner.isAppConfigFile(fileName)
        val keys = PropertiesUsageScanner.extractKeysFromProperty(property, includeKey)
        if (keys.isEmpty()) return

        val leaf = PsiTreeUtil.getDeepestFirst(property)
        ConfigMapMarkerHelper.buildMarkerForKeys(leaf, keys)?.let { result.add(it) }
    }
}
