package com.jamilxt.k8sconfigmap.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue

enum class UsageKind(val label: String) {
    VALUE_ANNOTATION("@Value"),
    CONFIGURATION_PROPERTIES("@ConfigurationProperties"),
    CONFIG_PROPERTY("@ConfigProperty"),
    ENVIRONMENT_API("Environment"),
    SYSTEM_GETENV("System.getenv"),
    ENV_VAR_CALL("Env Var"),
    CONFIG_PLACEHOLDER("Config placeholder"),
    CONFIG_OVERRIDE("Config override"),
    DOTENV_KEY(".env")
}

data class ConfigMapEntry(
    val key: String,
    val canonicalKey: String,
    val valuePreview: String,
    val configMapName: String,
    val namespace: String?,
    val virtualFile: VirtualFile,
    val psiPointer: SmartPsiElementPointer<YAMLKeyValue>
) {
    fun displayName(): String {
        val nsPrefix = if (!namespace.isNullOrBlank()) "$namespace/" else ""
        return "$nsPrefix$configMapName ($key)"
    }
}

data class ConfigMapTarget(
    val name: String,
    val namespace: String?,
    val virtualFile: VirtualFile,
    val documentPointer: SmartPsiElementPointer<YAMLDocument>,
    val existingKeys: Map<String, String>
) {
    fun displayName(): String {
        val nsPrefix = if (!namespace.isNullOrBlank()) "$namespace/" else ""
        return "$nsPrefix$name"
    }
}

data class ProjectUsage(
    val rawKey: String,
    val canonicalKey: String,
    val usageKind: UsageKind,
    val displayLocation: String,
    val psiPointer: SmartPsiElementPointer<PsiElement>
)
