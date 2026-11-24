package com.jamilxt.k8sconfigmap.navigation

import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.YamlConfigUsageScanner
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings

/**
 * Contributes matching K8s ConfigMap YAML keys to IntelliJ's Show Usages (`Ctrl+Click` on a property key)
 * and Find Usages (`Alt+F7`) for `.properties` files (`application*.properties`, `bootstrap*.properties`),
 * so both Java/Kotlin usages and ConfigMap entries appear together.
 */
class PropertiesConfigMapReferencesSearch :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val property = queryParameters.elementToSearch as? Property ?: return
        val fileName = property.containingFile?.name ?: return
        val project = property.project
        val settings = NavigatorSettings.getInstance(project)
        if (!settings.matchApplicationPropertyOverrides || !YamlConfigUsageScanner.isAppConfigFile(fileName)) return

        val key = property.key?.trim()
        if (key.isNullOrEmpty()) return

        val cmIndex = ConfigMapIndexService.getInstance(project)
        val seen = mutableSetOf<PsiElement>()
        for (entry in cmIndex.findEntriesByKey(key)) {
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
                override fun resolve(): PsiElement = property
                override fun isReferenceTo(element: PsiElement): Boolean =
                    element == property || element.manager.areElementsEquivalent(element, property)
                override fun handleElementRename(newElementName: String): PsiElement = element
                override fun bindToElement(element: PsiElement): PsiElement = this.element
            }
            if (!consumer.process(ref)) return
        }
    }
}
