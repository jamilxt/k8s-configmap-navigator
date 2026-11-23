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
                    val keys = PropertyKeyNormalizer.extractPlaceholders(rawText)
                    ConfigMapMarkerHelper.buildMarkerForKeys(PsiTreeUtil.getDeepestFirst(element), keys)
                        ?.let { result.add(it) }
                } else if (JvmUastUsageScanner.isConfigPropertyAnnotation(uElement)) {
                    val name = uElement.findDeclaredAttributeValue("name")?.evaluateString()?.trim() ?: return
                    ConfigMapMarkerHelper.buildMarkerForKeys(PsiTreeUtil.getDeepestFirst(element), listOf(name))
                        ?.let { result.add(it) }
                }
            }

            is UField -> {
                if (uElement.isStatic) return
                val fullKeys = JvmUastUsageScanner.resolveConfigPropertiesFieldKeys(uElement)
                if (fullKeys.isEmpty()) return
                val anchor = uElement.uastAnchor?.sourcePsi ?: element
                ConfigMapMarkerHelper.buildMarkerForKeys(PsiTreeUtil.getDeepestFirst(anchor), fullKeys)
                    ?.let { result.add(it) }
            }

            is UMethod -> {
                val fullKeys = JvmUastUsageScanner.resolveBeanConfigPropertiesKeys(uElement)
                if (fullKeys.isEmpty()) return
                val anchor = uElement.uastAnchor?.sourcePsi ?: element
                ConfigMapMarkerHelper.buildMarkerForKeys(PsiTreeUtil.getDeepestFirst(anchor), fullKeys)
                    ?.let { result.add(it) }
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
                    ConfigMapMarkerHelper.buildMarkerForKeys(PsiTreeUtil.getDeepestFirst(anchor), listOf(key))
                        ?.let { result.add(it) }
                }
            }
        }
    }
}
