package com.jamilxt.k8sconfigmap.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ProjectUsage
import com.jamilxt.k8sconfigmap.model.UsageKind
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.nio.file.InvalidPathException
import java.nio.file.Path

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
            if (isConfigurationPropertiesAnnotation(annotation)) {
                val method = annotation.uastParent as? UMethod
                if (method != null) {
                    val keys = resolveBeanConfigPropertiesKeys(method)
                    if (keys.isNotEmpty()) return keys
                }
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
            val annotatedKeys = extractKeysFromAnnotations(field.uAnnotations)
            if (annotatedKeys.isNotEmpty()) return annotatedKeys
            val keys = resolveConfigPropertiesFieldKeys(field)
            if (keys.isNotEmpty()) return keys
        }

        val param = uElement.getParentOfType(UParameter::class.java, false)
        if (param != null) {
            val annotatedKeys = extractKeysFromAnnotations(param.uAnnotations)
            if (annotatedKeys.isNotEmpty()) return annotatedKeys
        }

        val method = uElement.getParentOfType(UMethod::class.java, false)
        if (method != null && isOnMethodAnchor(element, method)) {
            val keys = resolveBeanConfigPropertiesKeys(method)
            if (keys.isNotEmpty()) return keys
        }

        return emptyList()
    }

    override fun extractDefaultValueAtElement(element: PsiElement, offset: Int, settings: NavigatorSettings): String? {
        val uElement = element.toUElement() ?: element.parent?.toUElement() ?: return null

        val annotation = uElement.getParentOfType(UAnnotation::class.java, false)
        if (annotation != null) {
            extractDefaultFromAnnotation(annotation)?.let { return it }
        }

        val call = uElement.getParentOfType(UCallExpression::class.java, false)
        if (call != null && call.valueArguments.size >= 2) {
            val methodName = call.methodName
            val isEnvOrSys = ((methodName == "getProperty") && isEnvironmentReceiver(call)) ||
                ((methodName == "getProperty") && settings.matchSystemGetEnv && isSystemReceiver(call))
            if (isEnvOrSys) {
                call.valueArguments[1].evaluateString()?.let { return it }
            }
        }

        val field = uElement.getParentOfType(UField::class.java, false)
        if (field != null && !field.isStatic) {
            for (ann in field.uAnnotations) {
                extractDefaultFromAnnotation(ann)?.let { return it }
            }
            val initVal = field.uastInitializer?.evaluate()
            if (initVal != null) return initVal.toString()
        }

        val param = uElement.getParentOfType(UParameter::class.java, false)
        if (param != null) {
            for (ann in param.uAnnotations) {
                extractDefaultFromAnnotation(ann)?.let { return it }
            }
        }

        return null
    }

    private fun extractKeysFromAnnotations(annotations: List<UAnnotation>): List<String> {
        val keys = LinkedHashSet<String>()
        for (ann in annotations) {
            if (isValueAnnotation(ann)) {
                val expr = ann.findAttributeValue("value")
                    ?: ann.attributeValues.firstOrNull()?.expression
                val rawText = expr?.evaluateString()
                if (rawText != null) keys.addAll(PropertyKeyNormalizer.extractPlaceholders(rawText))
            } else if (isConfigPropertyAnnotation(ann)) {
                val name = ann.findDeclaredAttributeValue("name")?.evaluateString()
                if (!name.isNullOrBlank()) keys.add(name.trim())
            }
        }
        return keys.toList()
    }

    private fun extractDefaultFromAnnotation(annotation: UAnnotation): String? {
        if (isValueAnnotation(annotation)) {
            val expr = annotation.findAttributeValue("value")
                ?: annotation.attributeValues.firstOrNull()?.expression
            val rawText = expr?.evaluateString() ?: return null
            return PropertyKeyNormalizer.extractPlaceholdersWithDefaults(rawText)
                .firstOrNull { it.second != null }?.second
        }
        if (isConfigPropertyAnnotation(annotation)) {
            return annotation.findDeclaredAttributeValue("defaultValue")?.evaluateString()
        }
        return null
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

            override fun visitMethod(node: UMethod): Boolean {
                val cpAnnotation = node.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) }
                if (cpAnnotation != null) {
                    val prefix = extractConfigurationPropertiesPrefix(cpAnnotation)
                    val returnPsiClass = (node.returnType as? PsiClassType)?.resolve()
                    val methodAnchor = node.uastAnchor?.sourcePsi ?: node.sourcePsi
                    if (returnPsiClass != null && methodAnchor != null) {
                        val ownerName = (node.uastParent as? UClass)?.name ?: fileName
                        val methodDisplay = "$ownerName.${node.name}()"
                        val returnClassName = returnPsiClass.name ?: returnPsiClass.qualifiedName.orEmpty()
                        val props = collectPropertyDescriptors(returnPsiClass, prefix, mutableSetOf())
                        for (prop in props) {
                            emit(
                                sink,
                                pointerManager,
                                prop.fullKey,
                                UsageKind.CONFIGURATION_PROPERTIES,
                                "$methodDisplay → ${prop.fieldPath}",
                                methodAnchor
                            )
                            if (prop.fieldPsi != null) {
                                emit(
                                    sink,
                                    pointerManager,
                                    prop.fullKey,
                                    UsageKind.CONFIGURATION_PROPERTIES,
                                    "$returnClassName.${prop.fieldPath}",
                                    prop.fieldPsi
                                )
                            }
                        }
                    }
                }
                return super.visitMethod(node)
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

        private val BEAN_CONFIG_PREFIXES_KEY =
            Key.create<CachedValue<Map<String, List<String>>>>("k8sConfigMap.beanConfigPropertiesPrefixes")

        data class ConfigPropertyDescriptor(
            val fullKey: String,
            val fieldPath: String,
            val fieldPsi: PsiElement?
        )

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

        fun resolveConfigPropertiesFieldKey(field: UField): String? =
            resolveConfigPropertiesFieldKeys(field).firstOrNull()

        fun resolveConfigPropertiesFieldKeys(field: UField): List<String> {
            val containingClass = field.uastParent as? UClass ?: return emptyList()
            val fieldName = field.name
            if (fieldName.isEmpty()) return emptyList()

            val keys = LinkedHashSet<String>()

            val directCp = containingClass.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) }
            if (directCp != null) {
                val prefix = extractConfigurationPropertiesPrefix(directCp)
                keys.add(if (prefix.isBlank()) fieldName else "$prefix.$fieldName")
            }

            val project = field.sourcePsi?.project
            if (project != null) {
                val classFqn = containingClass.qualifiedName ?: containingClass.name
                if (!classFqn.isNullOrEmpty()) {
                    for (beanPrefix in getBeanConfigPrefixesByType(project)[classFqn].orEmpty()) {
                        keys.add(if (beanPrefix.isBlank()) fieldName else "$beanPrefix.$fieldName")
                    }
                }
            }

            val outerClass = containingClass.uastParent as? UClass
            if (outerClass != null) {
                val matchingOuterField = outerClass.fields.firstOrNull { outerField ->
                    outerField.type.presentableText.substringBefore('<').substringAfterLast('.') == containingClass.name
                }
                if (matchingOuterField != null) {
                    val outerCp = outerClass.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) }
                    if (outerCp != null) {
                        val outerPrefix = extractConfigurationPropertiesPrefix(outerCp)
                        val midPrefix = if (outerPrefix.isBlank()) matchingOuterField.name else "$outerPrefix.${matchingOuterField.name}"
                        keys.add("$midPrefix.$fieldName")
                    }
                    if (project != null) {
                        val outerFqn = outerClass.qualifiedName ?: outerClass.name
                        if (!outerFqn.isNullOrEmpty()) {
                            for (beanPrefix in getBeanConfigPrefixesByType(project)[outerFqn].orEmpty()) {
                                val midPrefix = if (beanPrefix.isBlank()) matchingOuterField.name else "$beanPrefix.${matchingOuterField.name}"
                                keys.add("$midPrefix.$fieldName")
                            }
                        }
                    }
                }
            }

            return keys.toList()
        }

        fun resolveBeanConfigPropertiesKeys(method: UMethod): List<String> {
            val cpAnnotation = method.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) }
                ?: return emptyList()
            val prefix = extractConfigurationPropertiesPrefix(cpAnnotation)
            val returnPsiClass = (method.returnType as? PsiClassType)?.resolve() ?: return emptyList()
            return collectPropertyDescriptors(returnPsiClass, prefix, mutableSetOf())
                .map { it.fullKey }
                .distinct()
        }

        fun isOnMethodAnchor(element: PsiElement, method: UMethod): Boolean {
            val anchor = method.uastAnchor?.sourcePsi ?: return false
            return element == anchor || PsiTreeUtil.isAncestor(anchor, element, false)
        }

        fun collectPropertyDescriptors(
            psiClass: PsiClass,
            prefix: String,
            visited: MutableSet<String>,
            fieldPathPrefix: String = ""
        ): List<ConfigPropertyDescriptor> {
            val fqn = psiClass.qualifiedName ?: psiClass.name ?: ""
            if (fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("kotlin.")) return emptyList()
            if (fqn.isNotEmpty() && !visited.add(fqn)) return emptyList()

            val result = mutableListOf<ConfigPropertyDescriptor>()
            val uClass = psiClass.toUElement(UClass::class.java)
            if (uClass != null && uClass.fields.any { !it.isStatic }) {
                val innerByName = uClass.innerClasses.associateBy { it.name }
                val seenFields = mutableSetOf<String>()
                for (field in uClass.fields) {
                    if (field.isStatic) continue
                    val fieldName = field.name
                    if (fieldName.isEmpty() || !seenFields.add(fieldName)) continue
                    val fullKey = if (prefix.isBlank()) fieldName else "$prefix.$fieldName"
                    val fieldPath = if (fieldPathPrefix.isEmpty()) fieldName else "$fieldPathPrefix.$fieldName"
                    result.add(ConfigPropertyDescriptor(fullKey, fieldPath, field.sourcePsi))

                    val typeSimpleName = field.type.presentableText.substringBefore('<').substringAfterLast('.')
                    val nestedClass = innerByName[typeSimpleName]
                    if (nestedClass != null) {
                        result.addAll(collectPropertyDescriptors(nestedClass.javaPsi, fullKey, visited, fieldPath))
                    }
                }
                return result
            }

            // Fallback for compiled / library classes without UAST source fields
            val seen = mutableSetOf<String>()
            for (field in psiClass.allFields) {
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue
                if (field.containingClass?.qualifiedName == "java.lang.Object") continue
                val fieldName = field.name
                if (fieldName.isEmpty() || !seen.add(fieldName)) continue
                val fullKey = if (prefix.isBlank()) fieldName else "$prefix.$fieldName"
                val fieldPath = if (fieldPathPrefix.isEmpty()) fieldName else "$fieldPathPrefix.$fieldName"
                result.add(ConfigPropertyDescriptor(fullKey, fieldPath, null))
            }
            if (result.isEmpty()) {
                for (method in psiClass.allMethods) {
                    if (method.hasModifierProperty(PsiModifier.STATIC)) continue
                    if (method.parameterList.parametersCount != 1) continue
                    val name = method.name
                    if (!name.startsWith("set") || name.length <= 3 || !name[3].isUpperCase()) continue
                    val propName = name.substring(3).replaceFirstChar { it.lowercaseChar() }
                    if (!seen.add(propName)) continue
                    val fullKey = if (prefix.isBlank()) propName else "$prefix.$propName"
                    val fieldPath = if (fieldPathPrefix.isEmpty()) propName else "$fieldPathPrefix.$propName"
                    result.add(ConfigPropertyDescriptor(fullKey, fieldPath, null))
                }
            }
            return result
        }

        fun getBeanConfigPrefixesByType(project: Project): Map<String, List<String>> {
            val settings = NavigatorSettings.getInstance(project)
            return CachedValuesManager.getManager(project).getCachedValue(
                project,
                BEAN_CONFIG_PREFIXES_KEY,
                {
                    val map = buildBeanConfigPrefixesMap(project, settings)
                    CachedValueProvider.Result.create(
                        map,
                        PsiModificationTracker.MODIFICATION_COUNT,
                        settings.modificationTracker
                    )
                },
                false
            )
        }

        private fun buildBeanConfigPrefixesMap(
            project: Project,
            settings: NavigatorSettings
        ): Map<String, List<String>> {
            val scope = GlobalSearchScope.projectScope(project)
            val codeFiles = LinkedHashSet<VirtualFile>()
            codeFiles.addAll(FilenameIndex.getAllFilesByExt(project, "java", scope))
            codeFiles.addAll(FilenameIndex.getAllFilesByExt(project, "kt", scope))

            val externalRoots = settings.externalProjectPaths.mapNotNull { pathStr ->
                try {
                    LocalFileSystem.getInstance().findFileByNioFile(Path.of(pathStr))
                } catch (_: InvalidPathException) {
                    null
                }
            }
            UsageIndexService.collectExternalFiles(
                externalRoots,
                predicate = { vf -> val ext = vf.extension?.lowercase(); ext == "java" || ext == "kt" },
                sink = codeFiles
            )

            val psiManager = PsiManager.getInstance(project)
            val result = mutableMapOf<String, LinkedHashSet<String>>()
            for (vf in codeFiles) {
                if (!vf.isValid || vf.isDirectory) continue
                val psiFile = psiManager.findFile(vf) ?: continue
                val uFile = psiFile.toUElement(UFile::class.java) ?: continue
                uFile.accept(object : AbstractUastVisitor() {
                    override fun visitMethod(node: UMethod): Boolean {
                        val cpAnnotation = node.uAnnotations.firstOrNull { isConfigurationPropertiesAnnotation(it) }
                        if (cpAnnotation != null) {
                            val prefix = extractConfigurationPropertiesPrefix(cpAnnotation)
                            val returnPsiClass = (node.returnType as? PsiClassType)?.resolve()
                            val fqn = returnPsiClass?.qualifiedName ?: returnPsiClass?.name
                            if (!fqn.isNullOrEmpty()) {
                                result.computeIfAbsent(fqn) { LinkedHashSet() }.add(prefix)
                            }
                        }
                        return super.visitMethod(node)
                    }
                })
            }
            return result.mapValues { it.value.toList() }
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
