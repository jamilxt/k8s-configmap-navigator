package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.index.JvmUastUsageScanner
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.toUElement

class SpringCodeToConfigMapLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val uElement = element.toUElement() ?: return
        val project = element.project

        when (uElement) {
            is UAnnotation -> {
                if (JvmUastUsageScanner.isValueAnnotation(uElement)) {
                    val expr = uElement.findAttributeValue("value")
                        ?: uElement.attributeValues.firstOrNull()?.expression
                    val rawText = expr?.evaluateString() ?: return
                    val pairs = PropertyKeyNormalizer.extractPlaceholdersWithDefaults(rawText)
                    val keys = pairs.map { it.first }
                    val defaultVal = pairs.firstOrNull { it.second != null }?.second
                    ConfigMapMarkerHelper.buildMarkerForKeys(
                        PsiTreeUtil.getDeepestFirst(element),
                        keys,
                        allowAddMarker = true,
                        defaultValue = defaultVal
                    )?.let { result.add(it) }
                } else if (JvmUastUsageScanner.isConfigPropertyAnnotation(uElement)) {
                    val name = uElement.findDeclaredAttributeValue("name")?.evaluateString()?.trim() ?: return
                    val defaultVal = uElement.findDeclaredAttributeValue("defaultValue")?.evaluateString()
                    ConfigMapMarkerHelper.buildMarkerForKeys(
                        PsiTreeUtil.getDeepestFirst(element),
                        listOf(name),
                        allowAddMarker = true,
                        defaultValue = defaultVal
                    )?.let { result.add(it) }
                }
            }

            is UField -> {
                if (uElement.isStatic) return
                val fullKeys = JvmUastUsageScanner.resolveConfigPropertiesFieldKeys(uElement)
                if (fullKeys.isEmpty()) return
                val anchor = uElement.uastAnchor?.sourcePsi ?: element
                val defaultVal = uElement.uastInitializer?.evaluate()?.toString()
                ConfigMapMarkerHelper.buildMarkerForKeys(
                    PsiTreeUtil.getDeepestFirst(anchor),
                    fullKeys,
                    allowAddMarker = true,
                    defaultValue = defaultVal
                )?.let { result.add(it) }
            }

            is UMethod -> {
                val fullKeys = JvmUastUsageScanner.resolveBeanConfigPropertiesKeys(uElement)
                if (fullKeys.isEmpty()) return
                val anchor = uElement.uastAnchor?.sourcePsi ?: element
                ConfigMapMarkerHelper.buildMarkerForKeys(
                    PsiTreeUtil.getDeepestFirst(anchor),
                    fullKeys,
                    allowAddMarker = true
                )?.let { result.add(it) }
            }

            is UCallExpression -> {
                val methodName = uElement.methodName ?: return
                val firstArg = uElement.valueArguments.firstOrNull() ?: return
                val key = firstArg.evaluateString() ?: return
                val settings = NavigatorSettings.getInstance(project)
                val matches = when {
                    (methodName == "getProperty" || methodName == "getRequiredProperty") &&
                        JvmUastUsageScanner.isEnvironmentReceiver(uElement) -> true
                    (methodName == "getenv" || methodName == "getProperty") && settings.matchSystemGetEnv &&
                        JvmUastUsageScanner.isSystemReceiver(uElement) -> true
                    else -> false
                }
                if (matches) {
                    val anchor = firstArg.sourcePsi ?: element
                    val defaultVal = if (methodName == "getProperty" && uElement.valueArguments.size >= 2) {
                        uElement.valueArguments[1].evaluateString()
                    } else {
                        null
                    }
                    ConfigMapMarkerHelper.buildMarkerForKeys(
                        PsiTreeUtil.getDeepestFirst(anchor),
                        listOf(key),
                        allowAddMarker = true,
                        defaultValue = defaultVal
                    )?.let { result.add(it) }
                }
            }
        }
    }
}
