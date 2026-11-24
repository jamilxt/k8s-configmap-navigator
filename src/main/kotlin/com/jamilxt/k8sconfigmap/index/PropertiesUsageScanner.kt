package com.jamilxt.k8sconfigmap.index

import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import com.jamilxt.k8sconfigmap.model.UsageKind
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings

/**
 * Optional scanner for `.properties` files (registered when com.intellij.properties is available).
 */
class PropertiesUsageScanner : UsageScanner {

    override fun collectUsages(
        project: Project,
        externalRoots: List<VirtualFile>,
        settings: NavigatorSettings,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit
    ) {
        val scope = GlobalSearchScope.projectScope(project)
        val propFiles = LinkedHashSet<VirtualFile>()
        propFiles.addAll(FilenameIndex.getAllFilesByExt(project, "properties", scope))

        UsageIndexService.collectExternalFiles(
            externalRoots,
            predicate = { vf -> vf.extension?.lowercase() == "properties" },
            sink = propFiles
        )

        val psiManager = PsiManager.getInstance(project)
        for (vf in propFiles) {
            if (!vf.isValid || vf.isDirectory) continue
            val propFile = psiManager.findFile(vf) as? PropertiesFile ?: continue
            val isAppConfig = YamlConfigUsageScanner.isAppConfigFile(vf.name)
            for (prop in propFile.properties) {
                val key = prop.key?.trim() ?: continue
                val psiElement = prop.psiElement
                val value = prop.value
                if (value != null) {
                    for (ph in PropertyKeyNormalizer.extractPlaceholders(value)) {
                        val canonical = PropertyKeyNormalizer.canonicalize(ph)
                        if (canonical.isNotEmpty()) {
                            sink(
                                ProjectUsage(
                                    rawKey = ph,
                                    canonicalKey = canonical,
                                    usageKind = UsageKind.CONFIG_PLACEHOLDER,
                                    displayLocation = "${vf.name} ($key)",
                                    psiPointer = pointerManager.createSmartPsiElementPointer(psiElement)
                                )
                            )
                        }
                    }
                }
                if (isAppConfig && settings.matchApplicationPropertyOverrides) {
                    val canonical = PropertyKeyNormalizer.canonicalize(key)
                    if (canonical.isNotEmpty()) {
                        sink(
                            ProjectUsage(
                                rawKey = key,
                                canonicalKey = canonical,
                                usageKind = UsageKind.CONFIG_OVERRIDE,
                                displayLocation = "${vf.name} ($key)",
                                psiPointer = pointerManager.createSmartPsiElementPointer(psiElement)
                            )
                        )
                    }
                }
            }
        }
    }

    override fun extractKeysAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): List<String> {
        val property = PsiTreeUtil.getParentOfType(element, Property::class.java, false) ?: return emptyList()
        val fileName = property.containingFile?.name.orEmpty()
        val includeKey = settings.matchApplicationPropertyOverrides && YamlConfigUsageScanner.isAppConfigFile(fileName)
        return extractKeysFromProperty(property, includeKey)
    }

    override fun extractKeysForGotoDeclaration(
        element: PsiElement,
        offset: Int,
        settings: NavigatorSettings
    ): List<String> {
        val property = PsiTreeUtil.getParentOfType(element, Property::class.java, false) ?: return emptyList()
        // Do NOT intercept Ctrl+Click on the property key itself, so IntelliJ's built-in Show Usages
        // (showing Java/Kotlin @Value usages + ConfigMap entries via PropertiesConfigMapReferencesSearch) works.
        val keyEl = property.firstChild
        if (keyEl != null && (element == keyEl || PsiTreeUtil.isAncestor(keyEl, element, false))) {
            return emptyList()
        }
        val value = property.value ?: return emptyList()
        return PropertyKeyNormalizer.extractPlaceholders(value).distinct()
    }

    companion object {
        fun extractKeysFromProperty(property: Property, includeKey: Boolean): List<String> {
            val keys = mutableListOf<String>()
            property.value?.let { keys.addAll(PropertyKeyNormalizer.extractPlaceholders(it)) }
            if (includeKey) {
                property.key?.trim()?.takeIf { it.isNotEmpty() }?.let { keys.add(it) }
            }
            return keys.distinct()
        }
    }
}
