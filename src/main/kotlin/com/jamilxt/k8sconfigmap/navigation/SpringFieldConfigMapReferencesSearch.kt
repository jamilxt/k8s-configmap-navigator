package com.jamilxt.k8sconfigmap.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.JvmUastUsageScanner
import org.jetbrains.uast.UField
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElement

/**
 * Contributes matching K8s ConfigMap YAML keys to IntelliJ's Show Usages (`Ctrl+Click` on a declaration)
 * and Find Usages (`Alt+F7`) for JVM fields/parameters bound to config properties (`@Value`, `@ConfigProperty`,
 * `@ConfigurationProperties`).
 *
 * This allows `Ctrl+Click` on a field (including Lombok-backed fields) to display both code/Lombok usages
 * and matching ConfigMap entries together in the Usages popup.
 */
class SpringFieldConfigMapReferencesSearch :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val target = queryParameters.elementToSearch
        val keys = when (val uElement = target.toUElement()) {
            is UField -> {
                if (uElement.isStatic) return
                val annotated = JvmUastUsageScanner.extractKeysFromAnnotations(uElement.uAnnotations)
                if (annotated.isNotEmpty()) annotated else JvmUastUsageScanner.resolveConfigPropertiesFieldKeys(uElement)
            }
            is UParameter -> JvmUastUsageScanner.extractKeysFromAnnotations(uElement.uAnnotations)
            else -> return
        }
        if (keys.isEmpty()) return

        val cmIndex = ConfigMapIndexService.getInstance(target.project)
        val seen = mutableSetOf<PsiElement>()
        for (key in keys.distinct()) {
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
                    override fun resolve(): PsiElement = target
                    override fun isReferenceTo(element: PsiElement): Boolean =
                        element == target || element.manager.areElementsEquivalent(element, target)
                    override fun handleElementRename(newElementName: String): PsiElement = element
                }
                if (!consumer.process(ref)) return
            }
        }
    }
}
