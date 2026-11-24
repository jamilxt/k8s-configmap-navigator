package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.FrameworkPropertyIndexService
import com.jamilxt.k8sconfigmap.index.UsageIndexService
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Highlights Kubernetes ConfigMap `data:` keys that are neither referenced in project code/config
 * nor recognized as framework/runtime properties as unused (grayed out).
 */
class UnusedConfigMapKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val project = holder.project
        val cmIndex = ConfigMapIndexService.getInstance(project)
        val usageIndex = UsageIndexService.getInstance(project)
        val frameworkIndex = FrameworkPropertyIndexService.getInstance(project)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val keyValue = element as? YAMLKeyValue ?: return
                if (!cmIndex.isConfigMapDataKey(keyValue)) return
                val key = keyValue.keyText.trim()
                if (key.isEmpty()) return

                if (usageIndex.findUsagesForConfigMapKey(key).isNotEmpty()) return
                if (frameworkIndex.findFrameworkProperty(key) != null) return

                val targetElement = keyValue.key ?: keyValue
                holder.registerProblem(
                    targetElement,
                    "ConfigMap key '$key' is not used in project code or recognized as a framework property",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL
                )
            }
        }
    }
}
