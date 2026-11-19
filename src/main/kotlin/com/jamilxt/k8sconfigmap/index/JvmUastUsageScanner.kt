package com.jamilxt.k8sconfigmap.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import com.jamilxt.k8sconfigmap.model.UsageKind
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UFile
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Optional UAST-based scanner for JVM languages (Java & Kotlin).
 * Supports Spring Boot (@Value, @ConfigurationProperties, Environment.getProperty),
 * Quarkus / MicroProfile (@ConfigProperty), Micronaut (@Value), and System.getenv / System.getProperty.
 */
class JvmUastUsageScanner : UsageScanner {

    override fun collectUsages(
        project: Project,
        externalRoots: List<VirtualFile>,
        settings: NavigatorSettings,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit
    ) {
        val scope = GlobalSearchScope.projectScope(project)
        val codeFiles = LinkedHashSet<VirtualFile>()
        codeFiles.addAll(FilenameIndex.getAllFilesByExt(project, "java", scope))
        codeFiles.addAll(FilenameIndex.getAllFilesByExt(project, "kt", scope))

        UsageIndexService.collectExternalFiles(
            externalRoots,
            predicate = { vf -> val ext = vf.extension?.lowercase(); ext == "java" || ext == "kt" },
            sink = codeFiles
        )

        val psiManager = PsiManager.getInstance(project)
        for (vf in codeFiles) {
            if (!vf.isValid || vf.isDirectory) continue
            val psiFile = psiManager.findFile(vf) ?: continue
            val uFile = psiFile.toUElement(UFile::class.java) ?: continue
            scanUastFile(uFile, vf.name, settings, pointerManager, sink)
        }
    }

    override fun extractKeysAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): List<String> {
        val uElement = element.toUElement() ?: element.parent?.toUElement() ?: return emptyList()

        val annotation = uElement.getParentOfType(UAnnotation::class.java, false)
        if (annotation != null) {
            if (isValueAnnotation(annotation)) {
                val expr = annotation.findAttributeValue("value")
                    ?: annotation.attributeValues.firstOrNull()?.expression
                val rawText = expr?.evaluateString()
                if (rawText != null) return PropertyKeyNormalizer.extractPlaceholders(rawText)
            }
            if (isConfigPropertyAnnotation(annotation)) {
                val name = annotation.findDeclaredAttributeValue("name")?.evaluateString()
                if (!name.isNullOrBlank()) return listOf(name.trim())
            }
        }

        val call = uElement.getParentOfType(UCallExpression::class.java, false)
        if (call != null) {
            val methodName = call.methodName
            val firstArg = call.valueArguments.firstOrNull()?.evaluateString()
            if (firstArg != null) {
                val matched = when {
                    (methodName == "getProperty" || methodName == "getRequiredProperty") && isEnvironmentReceiver(call) -> true
                    (methodName == "getenv" || methodName == "getProperty") && settings.matchSystemGetEnv && isSystemReceiver(call) -> true
                    else -> false
                }
                if (matched) return listOf(firstArg)
            }
        }

        val field = uElement.getParentOfType(UField::class.java, false)
        if (field != null && !field.isStatic) {
            resolveConfigPropertiesFieldKey(field)?.let { return listOf(it) }
        }

        return emptyList()
    }

    private fun scanUastFile(
        uFile: UFile,
        fileName: String,
        settings: NavigatorSettings,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit
    ) {
        uFile.accept(object : AbstractUastVisitor() {
            override fun visitAnnotation(node: UAnnotation): Boolean {
                if (isValueAnnotation(node)) {
                    val expr = node.findAttributeValue("value")
                        ?: node.attributeValues.firstOrNull()?.expression
                    val rawText = expr?.evaluateString()
                    val anchor = expr?.sourcePsi ?: node.sourcePsi
                    if (rawText != null && anchor != null) {
                        for (placeholder in PropertyKeyNormalizer.extractPlaceholders(rawText)) {
                            emit(sink, pointerManager, placeholder, UsageKind.VALUE_ANNOTATION, fileName, anchor)
                        }
                    }
                } else if (isConfigPropertyAnnotation(node)) {
                    val expr = node.findDeclaredAttributeValue("name")
                    val name = expr?.evaluateString()?.trim()
                    val anchor = expr?.sourcePsi ?: node.sourcePsi
                    if (!name.isNullOrEmpty() && anchor != null) {
                        emit(sink, pointerManager, name, UsageKind.CONFIG_PROPERTY, fileName, anchor)
                    }
                }
                return super.visitAnnotation(node)
            }

            override fun visitClass(node: UClass): Boolean {
                val cpAnnotation = node.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) }
                if (cpAnnotation != null) {
                    val prefix = extractConfigurationPropertiesPrefix(cpAnnotation)
                    val className = node.name ?: fileName
                    collectConfigurationPropertiesFields(node, prefix, className, pointerManager, sink, mutableSetOf())
                }
                return super.visitClass(node)
            }

            override fun visitCallExpression(node: UCallExpression): Boolean {
                val methodName = node.methodName ?: return super.visitCallExpression(node)
                val firstArg = node.valueArguments.firstOrNull() ?: return super.visitCallExpression(node)
                val argValue = firstArg.evaluateString() ?: return super.visitCallExpression(node)
                val anchor = firstArg.sourcePsi ?: node.sourcePsi ?: return super.visitCallExpression(node)

                if ((methodName == "getProperty" || methodName == "getRequiredProperty") && isEnvironmentReceiver(node)) {
                    emit(sink, pointerManager, argValue, UsageKind.ENVIRONMENT_API, fileName, anchor)
                } else if ((methodName == "getenv" || methodName == "getProperty") && settings.matchSystemGetEnv && isSystemReceiver(node)) {
                    emit(sink, pointerManager, argValue, UsageKind.SYSTEM_GETENV, fileName, anchor)
                }
                return super.visitCallExpression(node)
            }
        })
    }

    private fun collectConfigurationPropertiesFields(
        uClass: UClass,
        prefix: String,
        displayOwner: String,
        pointerManager: SmartPointerManager,
        sink: (ProjectUsage) -> Unit,
        visited: MutableSet<String>
    ) {
        val fqn = uClass.qualifiedName ?: uClass.name ?: ""
        if (fqn.isNotEmpty() && !visited.add(fqn)) return

        val innerByName = uClass.innerClasses.associateBy { it.name }
        val seenFields = mutableSetOf<String>()

        for (field in uClass.fields) {
            if (field.isStatic) continue
            val fieldName = field.name
            if (fieldName.isEmpty() || !seenFields.add(fieldName)) continue
            val fullKey = if (prefix.isBlank()) fieldName else "$prefix.$fieldName"
            val anchor = field.sourcePsi ?: continue

            emit(sink, pointerManager, fullKey, UsageKind.CONFIGURATION_PROPERTIES, "$displayOwner.$fieldName", anchor)

            val typeSimpleName = field.type.presentableText.substringBefore('<').substringAfterLast('.')
            val nestedClass = innerByName[typeSimpleName]
            if (nestedClass != null) {
                collectConfigurationPropertiesFields(nestedClass, fullKey, "$displayOwner.$typeSimpleName", pointerManager, sink, visited)
            }
        }
    }

    private fun emit(
        sink: (ProjectUsage) -> Unit,
        pointerManager: SmartPointerManager,
        rawKey: String,
        kind: UsageKind,
        displayLocation: String,
        element: PsiElement
    ) {
        val canonical = PropertyKeyNormalizer.canonicalize(rawKey)
        if (canonical.isEmpty()) return
        sink(
            ProjectUsage(
                rawKey = rawKey,
                canonicalKey = canonical,
                usageKind = kind,
                displayLocation = displayLocation,
                psiPointer = pointerManager.createSmartPsiElementPointer(element)
            )
        )
    }

    companion object {
        private val VALUE_FQNS = setOf(
            "org.springframework.beans.factory.annotation.Value",
            "io.micronaut.context.annotation.Value"
        )
        private val CONFIG_PROPS_FQNS = setOf(
            "org.springframework.boot.context.properties.ConfigurationProperties",
            "io.micronaut.context.annotation.ConfigurationProperties"
        )
        private const val MICROPROFILE_CONFIG_PROPERTY_FQN = "org.eclipse.microprofile.config.inject.ConfigProperty"

        fun isValueAnnotation(annotation: UAnnotation): Boolean {
            val fqn = annotation.qualifiedName
            if (fqn in VALUE_FQNS) return true
            return fqn == null && annotation.sourcePsi?.text?.startsWith("@Value") == true
        }

        fun isConfigPropertyAnnotation(annotation: UAnnotation): Boolean {
            val fqn = annotation.qualifiedName
            if (fqn == MICROPROFILE_CONFIG_PROPERTY_FQN) return true
            return fqn == null && annotation.sourcePsi?.text?.startsWith("@ConfigProperty") == true
        }

        fun isConfigurationPropertiesAnnotation(annotation: UAnnotation): Boolean {
            val fqn = annotation.qualifiedName
            if (fqn in CONFIG_PROPS_FQNS) return true
            return fqn == null && annotation.sourcePsi?.text?.startsWith("@ConfigurationProperties") == true
        }

        fun extractConfigurationPropertiesPrefix(annotation: UAnnotation): String {
            val prefixAttr = annotation.findDeclaredAttributeValue("prefix")
                ?: annotation.findDeclaredAttributeValue("value")
                ?: annotation.attributeValues.firstOrNull()?.expression
            return prefixAttr?.evaluateString()?.trim().orEmpty()
        }

        fun resolveConfigPropertiesFieldKey(field: UField): String? {
            val containingClass = field.uastParent as? UClass ?: return null
            val fieldName = field.name
            if (fieldName.isEmpty()) return null

            val directCp = containingClass.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) }
            if (directCp != null) {
                val prefix = extractConfigurationPropertiesPrefix(directCp)
                return if (prefix.isBlank()) fieldName else "$prefix.$fieldName"
            }

            val outerClass = containingClass.uastParent as? UClass ?: return null
            val outerCp = outerClass.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) } ?: return null
            val outerPrefix = extractConfigurationPropertiesPrefix(outerCp)
            val matchingOuterField = outerClass.fields.firstOrNull { outerField ->
                outerField.type.presentableText.substringBefore('<').substringAfterLast('.') == containingClass.name
            } ?: return null
            val midPrefix = if (outerPrefix.isBlank()) matchingOuterField.name else "$outerPrefix.${matchingOuterField.name}"
            return "$midPrefix.$fieldName"
        }

        fun isEnvironmentReceiver(call: UCallExpression): Boolean {
            val receiverType = call.receiverType?.canonicalText
            if (receiverType != null) {
                return receiverType.contains("Environment") || receiverType.contains("PropertyResolver")
            }
            val receiverText = call.receiver?.asSourceString()?.lowercase().orEmpty()
            return receiverText.contains("env") || receiverText.contains("environment")
        }

        fun isSystemReceiver(call: UCallExpression): Boolean {
            val receiverType = call.receiverType?.canonicalText
            if (receiverType == "java.lang.System") return true
            return call.receiver?.asSourceString() == "System"
        }
    }
}
