package com.jamilxt.k8sconfigmap.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.UsageIndexService
import org.jetbrains.yaml.psi.YAMLKeyValue

class ConfigMapGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        val project = sourceElement.project

        // Direction 1: From K8s ConfigMap YAML key -> any project usages (Java, Kotlin, JS/TS, Python, Go, YAML, .env, etc.)
        val yamlKv = PsiTreeUtil.getParentOfType(sourceElement, YAMLKeyValue::class.java, false)
        if (yamlKv != null && ConfigMapIndexService.getInstance(project).isConfigMapDataKey(yamlKv)) {
            val key = yamlKv.keyText.trim()
            val targets = UsageIndexService.getInstance(project)
                .findUsagesForConfigMapKey(key)
                .mapNotNull { it.psiPointer.element }
                .distinct()
            return if (targets.isNotEmpty()) targets.toTypedArray() else null
        }

        // Direction 2: From any supported language or config file -> K8s ConfigMap definition(s)
        val keys = UsageIndexService.getInstance(project).extractKeysAtElement(sourceElement, offset)
        if (keys.isEmpty()) return null

        val cmIndex = ConfigMapIndexService.getInstance(project)
        val targets = keys.distinct()
            .flatMap { cmIndex.findEntriesByKey(it) }
            .mapNotNull { it.psiPointer.element }
            .distinct()
        return if (targets.isNotEmpty()) targets.toTypedArray() else null
    }
}
