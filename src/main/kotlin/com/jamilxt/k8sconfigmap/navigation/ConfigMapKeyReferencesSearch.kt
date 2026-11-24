package com.jamilxt.k8sconfigmap.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.UsageIndexService
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Integrates K8s ConfigMap keys with JetBrains' native Find Usages (Alt+F7).
 * When Find Usages is invoked on a YAMLKeyValue under a ConfigMap's `data:` section,
 * yields synthetic references anchored at every matching usage across any supported language/config.
 */
class ConfigMapKeyReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val target = queryParameters.elementToSearch as? YAMLKeyValue ?: return
        val project = target.project
        val cmIndex = ConfigMapIndexService.getInstance(project)

        if (cmIndex.isConfigMapDataKey(target)) {
            val key = target.keyText.trim()
            if (key.isEmpty()) return

            val usages = UsageIndexService.getInstance(project).findUsagesForConfigMapKey(key)
            val seen = mutableSetOf<PsiElement>()
            for (usage in usages) {
                val usageElement = usage.psiPointer.element ?: continue
                if (!usageElement.isValid || !seen.add(usageElement)) continue
                val range = TextRange(0, usageElement.textLength.coerceAtLeast(1))
                val ref = object : PsiReferenceBase<PsiElement>(usageElement, range, true) {
                    override fun resolve(): PsiElement = target
                    override fun isReferenceTo(element: PsiElement): Boolean =
                        element == target || element.manager.areElementsEquivalent(element, target)
                    override fun handleElementRename(newElementName: String): PsiElement = element
                    override fun bindToElement(element: PsiElement): PsiElement = this.element
                }
                if (!consumer.process(ref)) return
            }
            return
        }

        // Reverse: Find Usages / Show Usages on an application*.yml / bootstrap*.yml key -> include matching ConfigMap keys
        val fileName = target.containingFile?.name.orEmpty()
        val settings = com.jamilxt.k8sconfigmap.settings.NavigatorSettings.getInstance(project)
        if (!settings.matchApplicationPropertyOverrides ||
            !com.jamilxt.k8sconfigmap.index.YamlConfigUsageScanner.isAppConfigFile(fileName) ||
            target.value is org.jetbrains.yaml.psi.YAMLMapping
        ) {
            return
        }
        val fullPath = com.jamilxt.k8sconfigmap.index.YamlConfigUsageScanner.computeYamlPropertyPath(target)
        if (fullPath.isEmpty()) return

        val seen = mutableSetOf<PsiElement>()
        for (entry in cmIndex.findEntriesByKey(fullPath)) {
            val yamlKv = entry.psiPointer.element ?: continue
            if (!yamlKv.isValid || !seen.add(yamlKv)) continue
            val keyElement = yamlKv.key
            val range = if (keyElement != null && keyElement.textRange.startOffset >= yamlKv.textRange.startOffset) {
                val start = keyElement.textRange.startOffset - yamlKv.textRange.startOffset
                TextRange(start, (start + keyElement.textLength).coerceAtLeast(start + 1))
            } else {
                TextRange(0, yamlKv.textLength.coerceAtLeast(1))
            }
            val ref = object : PsiReferenceBase<PsiElement>(yamlKv, range, true) {
                override fun resolve(): PsiElement = target
                override fun isReferenceTo(element: PsiElement): Boolean =
                    element == target || element.manager.areElementsEquivalent(element, target)
                override fun handleElementRename(newElementName: String): PsiElement = element
                override fun bindToElement(element: PsiElement): PsiElement = this.element
            }
            if (!consumer.process(ref)) return
        }
    }
}
