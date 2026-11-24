package com.jamilxt.k8sconfigmap.index

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer

enum class PropertyOrigin {
    FRAMEWORK,
    LIBRARY
}

data class FrameworkPropertyMatch(
    val propertyName: String,
    val frameworkName: String,
    val description: String? = null,
    val defaultValue: String? = null,
    val type: String? = null,
    val isPrefixMatch: Boolean = false,
    val origin: PropertyOrigin = PropertyOrigin.FRAMEWORK
)

/**
 * Identifies framework- and runtime-level configuration properties in Kubernetes ConfigMaps:
 * 1. Exact & Map-prefix matches from `META-INF/spring-configuration-metadata.json` (and
 *    `additional-spring-configuration-metadata.json`) on the project classpath.
 * 2. Well-known JVM, runtime, observability, and cloud/framework environment variables & prefixes.
 */
@Service(Service.Level.PROJECT)
class FrameworkPropertyIndexService(private val project: Project) {

    private data class MetadataIndex(
        val hasClasspathMetadata: Boolean,
        val exactByCanonical: Map<String, FrameworkPropertyMatch>,
        val mapPrefixes: List<Pair<List<String>, FrameworkPropertyMatch>>
    )

    private fun getMetadataIndex(): MetadataIndex {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val result = buildMetadataIndex()
            CachedValueProvider.Result.create(
                result,
                ProjectRootManager.getInstance(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            )
        }
    }

    fun findFrameworkProperty(configMapKey: String): FrameworkPropertyMatch? {
        val trimmed = configMapKey.trim()
        if (trimmed.isEmpty()) return null
        val canonical = PropertyKeyNormalizer.canonicalize(trimmed)
        if (canonical.isEmpty()) return null
        val tokens = PropertyKeyNormalizer.tokenize(trimmed)

        // 1. Check classpath Spring configuration metadata (exact, then Map/Properties prefix)
        val index = getMetadataIndex()
        index.exactByCanonical[canonical]?.let { match ->
            if (PropertyKeyNormalizer.matches(trimmed, match.propertyName)) {
                return match
            }
        }
        if (tokens.size >= 2 && index.mapPrefixes.isNotEmpty()) {
            var bestMatch: FrameworkPropertyMatch? = null
            var bestLen = 0
            for ((prefixTokens, match) in index.mapPrefixes) {
                if (prefixTokens.size < tokens.size &&
                    prefixTokens.size > bestLen &&
                    startsWithTokens(tokens, prefixTokens)
                ) {
                    bestLen = prefixTokens.size
                    bestMatch = match
                }
            }
            if (bestMatch != null) return bestMatch
        }

        // 2. Check well-known exact runtime / framework env vars
        BUILTIN_EXACT_BY_CANONICAL[canonical]?.let { return it }

        // 3. Check well-known runtime / observability / framework prefixes
        if (tokens.isNotEmpty()) {
            for ((prefixTokens, frameworkName) in BUILTIN_PREFIXES) {
                if (tokens.size >= prefixTokens.size && startsWithTokens(tokens, prefixTokens)) {
                    return FrameworkPropertyMatch(
                        propertyName = prefixTokens.joinToString(".") + ".*",
                        frameworkName = frameworkName,
                        isPrefixMatch = true
                    )
                }
            }
            // Fallback Spring Boot prefixes only when classpath metadata is absent (e.g. standalone K8s/Helm repo)
            if (!index.hasClasspathMetadata) {
                for ((prefixTokens, frameworkName) in SPRING_FALLBACK_PREFIXES) {
                    if (tokens.size >= prefixTokens.size && startsWithTokens(tokens, prefixTokens)) {
                        return FrameworkPropertyMatch(
                            propertyName = prefixTokens.joinToString(".") + ".*",
                            frameworkName = frameworkName,
                            isPrefixMatch = true
                        )
                    }
                }
            }
        }

        return null
    }

    private fun buildMetadataIndex(): MetadataIndex {
        val scope = GlobalSearchScope.allScope(project)
        val files = LinkedHashSet<VirtualFile>()
        files.addAll(FilenameIndex.getVirtualFilesByName("spring-configuration-metadata.json", scope))
        files.addAll(FilenameIndex.getVirtualFilesByName("additional-spring-configuration-metadata.json", scope))

        val exact = mutableMapOf<String, FrameworkPropertyMatch>()
        val mapPrefixes = mutableListOf<Pair<List<String>, FrameworkPropertyMatch>>()
        val seenMapPrefixes = mutableSetOf<String>()

        for (vf in files) {
            if (!vf.isValid || vf.isDirectory) continue
            parseMetadataFile(vf, exact, mapPrefixes, seenMapPrefixes)
        }

        return MetadataIndex(
            hasClasspathMetadata = files.isNotEmpty(),
            exactByCanonical = exact,
            mapPrefixes = mapPrefixes
        )
    }

    private fun parseMetadataFile(
        vf: VirtualFile,
        exact: MutableMap<String, FrameworkPropertyMatch>,
        mapPrefixes: MutableList<Pair<List<String>, FrameworkPropertyMatch>>,
        seenMapPrefixes: MutableSet<String>
    ) {
        val content = try {
            VfsUtilCore.loadText(vf)
        } catch (_: Exception) {
            return
        }
        val root = try {
            JsonParser.parseString(content).asJsonObject
        } catch (_: Exception) {
            return
        }
        val props = root.getAsJsonArray("properties") ?: return
        for (el in props) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: continue
            if (name.isEmpty()) continue
            val canonical = PropertyKeyNormalizer.canonicalize(name)
            if (canonical.isEmpty()) continue

            val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            val sourceType = obj.get("sourceType")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            val description = obj.get("description")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?.takeIf { it.isNotEmpty() }
            val defaultValue = obj.get("defaultValue")?.let { dv ->
                when {
                    dv.isJsonNull -> null
                    dv.isJsonPrimitive -> dv.asString
                    else -> dv.toString()
                }
            }

            val (origin, label) = classifyMetadataOrigin(vf, name, sourceType)
            val match = FrameworkPropertyMatch(
                propertyName = name,
                frameworkName = label,
                origin = origin,
                description = description,
                defaultValue = defaultValue,
                type = type
            )
            exact.putIfAbsent(canonical, match)

            if (type != null && (type.startsWith("java.util.Map") || type.startsWith("java.util.Properties"))) {
                val prefixTokens = PropertyKeyNormalizer.tokenize(name)
                if (prefixTokens.isNotEmpty() && seenMapPrefixes.add(canonical)) {
                    mapPrefixes.add(prefixTokens to match.copy(isPrefixMatch = true))
                }
            }
        }
    }

    private fun classifyMetadataOrigin(
        vf: VirtualFile,
        propertyName: String,
        sourceType: String?
    ): Pair<PropertyOrigin, String> {
        val jarName = extractJarName(vf.path)
        val src = sourceType.orEmpty()
        val propLower = propertyName.lowercase()

        if (src.startsWith("org.springframework.") ||
            src.startsWith("org.apache.catalina.") ||
            src.startsWith("org.eclipse.jetty.") ||
            src.startsWith("io.undertow.") ||
            jarName?.startsWith("spring-") == true
        ) {
            return PropertyOrigin.FRAMEWORK to "Spring Boot"
        }
        if (src.startsWith("io.micrometer.") || jarName?.startsWith("micrometer-") == true) {
            return PropertyOrigin.FRAMEWORK to "Micrometer"
        }
        if (src.startsWith("io.quarkus.") || jarName?.startsWith("quarkus-") == true) {
            return PropertyOrigin.FRAMEWORK to "Quarkus"
        }
        if (src.startsWith("io.micronaut.") || jarName?.startsWith("micronaut-") == true) {
            return PropertyOrigin.FRAMEWORK to "Micronaut"
        }

        // If sourceType is absent (e.g., manual metadata) and property uses standard Spring Boot root prefixes
        if (src.isEmpty() && jarName == null &&
            (propLower.startsWith("spring.") || propLower.startsWith("server.") ||
                propLower.startsWith("logging.") || propLower.startsWith("management."))
        ) {
            return PropertyOrigin.FRAMEWORK to "Spring Boot"
        }

        val libraryLabel = jarName
            ?: sourceType?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            ?: "Library"
        return PropertyOrigin.LIBRARY to libraryLabel
    }

    private fun extractJarName(path: String): String? {
        val bangIdx = path.indexOf("!/")
        if (bangIdx < 0) return null
        val jarPath = path.substring(0, bangIdx)
        val fileName = jarPath.substringAfterLast('/').substringAfterLast('\\')
        return fileName.removeSuffix(".jar").takeIf { it.isNotEmpty() }
    }

    private fun startsWithTokens(tokens: List<String>, prefix: List<String>): Boolean {
        for (i in prefix.indices) {
            if (tokens[i] != prefix[i]) return false
        }
        return true
    }

    companion object {
        fun getInstance(project: Project): FrameworkPropertyIndexService = project.service()

        private val BUILTIN_EXACT_LIST = listOf(
            // JVM / OS Runtime
            FrameworkPropertyMatch("JAVA_OPTS", "JVM / Runtime", "Additional options passed to the JVM."),
            FrameworkPropertyMatch("JAVA_TOOL_OPTIONS", "JVM / Runtime", "JVM tool and agent options picked up automatically by the JVM."),
            FrameworkPropertyMatch("JDK_JAVA_OPTIONS", "JVM / Runtime", "Options prepended to the java launcher command line."),
            FrameworkPropertyMatch("JAVA_HOME", "JVM / Runtime", "Path to the Java installation directory."),
            FrameworkPropertyMatch("CLASSPATH", "JVM / Runtime", "Java classpath."),
            FrameworkPropertyMatch("TZ", "OS / Runtime", "Container timezone setting."),
            FrameworkPropertyMatch("LANG", "OS / Runtime", "System locale setting."),
            FrameworkPropertyMatch("LC_ALL", "OS / Runtime", "Override for all locale categories."),
            // Spring Boot essentials
            FrameworkPropertyMatch("spring.profiles.active", "Spring Boot", "Comma-separated list of active Spring profiles."),
            FrameworkPropertyMatch("spring.profiles.default", "Spring Boot", "Default Spring profiles enabled when no active profile is set."),
            FrameworkPropertyMatch("spring.profiles.include", "Spring Boot", "Additional Spring profiles to unconditionally activate."),
            FrameworkPropertyMatch("spring.application.name", "Spring Boot", "Application name."),
            FrameworkPropertyMatch("spring.config.location", "Spring Boot", "Config file locations that replace the defaults."),
            FrameworkPropertyMatch("spring.config.additional-location", "Spring Boot", "Additional config file locations."),
            FrameworkPropertyMatch("spring.config.import", "Spring Boot", "Import additional configuration data."),
            FrameworkPropertyMatch("server.port", "Spring Boot", "Server HTTP port."),
            FrameworkPropertyMatch("management.server.port", "Spring Boot", "Management endpoint HTTP port."),
            // Node.js / Python / Go / .NET / Rust / Ruby
            FrameworkPropertyMatch("NODE_ENV", "Node.js", "Node.js runtime environment (e.g. production, development)."),
            FrameworkPropertyMatch("NODE_OPTIONS", "Node.js", "Command-line options passed to Node.js."),
            FrameworkPropertyMatch("PYTHONUNBUFFERED", "Python", "Force stdout/stderr streams to be unbuffered."),
            FrameworkPropertyMatch("PYTHONPATH", "Python", "Default search path for Python module files."),
            FrameworkPropertyMatch("PYTHONDONTWRITEBYTECODE", "Python", "Prevent Python from writing .pyc files."),
            FrameworkPropertyMatch("DJANGO_SETTINGS_MODULE", "Django", "Python path to the Django settings module."),
            FrameworkPropertyMatch("FLASK_APP", "Flask", "Entry point for the Flask application."),
            FrameworkPropertyMatch("FLASK_ENV", "Flask", "Flask runtime environment."),
            FrameworkPropertyMatch("GIN_MODE", "Go / Gin", "Gin web framework mode (debug, release, test)."),
            FrameworkPropertyMatch("GOMAXPROCS", "Go Runtime", "Maximum number of OS threads executing Go code simultaneously."),
            FrameworkPropertyMatch("GOMEMLIMIT", "Go Runtime", "Soft memory limit for the Go runtime."),
            FrameworkPropertyMatch("RUST_LOG", "Rust", "Log level filter for env_logger / tracing."),
            FrameworkPropertyMatch("RUST_BACKTRACE", "Rust", "Enable backtraces on panic."),
            FrameworkPropertyMatch("RAILS_ENV", "Ruby on Rails", "Rails runtime environment."),
            FrameworkPropertyMatch("RACK_ENV", "Ruby / Rack", "Rack runtime environment."),
            FrameworkPropertyMatch("GOOGLE_APPLICATION_CREDENTIALS", "Google Cloud", "Path to Google Cloud service account credentials JSON.")
        )

        private val BUILTIN_EXACT_BY_CANONICAL: Map<String, FrameworkPropertyMatch> =
            BUILTIN_EXACT_LIST.associateBy { PropertyKeyNormalizer.canonicalize(it.propertyName) }

        private val BUILTIN_PREFIXES: List<Pair<List<String>, String>> = listOf(
            listOf("otel") to "OpenTelemetry",
            listOf("dd") to "Datadog",
            listOf("new", "relic") to "New Relic",
            listOf("sentry") to "Sentry",
            listOf("aws") to "AWS SDK",
            listOf("azure") to "Azure SDK",
            listOf("kubernetes") to "Kubernetes",
            listOf("quarkus") to "Quarkus",
            listOf("micronaut") to "Micronaut",
            listOf("aspnetcore") to "ASP.NET Core",
            listOf("dotnet") to ".NET Runtime"
        )

        private val SPRING_FALLBACK_PREFIXES: List<Pair<List<String>, String>> = listOf(
            listOf("spring") to "Spring Boot",
            listOf("server") to "Spring Boot",
            listOf("logging") to "Spring Boot",
            listOf("management") to "Spring Boot"
        )
    }
}
