package com.jamilxt.k8sconfigmap.core

/**
 * Normalizes and matches Kubernetes ConfigMap keys (typically UPPER_SNAKE_CASE)
 * against Spring Boot property keys using Spring Boot's relaxed binding rules.
 *
 * Examples that all match:
 * - MY_APP_CONNECT_TIMEOUT
 * - my.app.connect-timeout
 * - my.app.connectTimeout
 * - my.app.connect_timeout
 * - MY_LIST_0_NAME <-> my.list[0].name
 */
object PropertyKeyNormalizer {

    private val PLACEHOLDER_REGEX = Regex("""\$\{([^}:]+)(?::[^}]*)?}""")
    private val ARRAY_INDEX_REGEX = Regex("""\[(\d+)]""")
    private val SEPARATOR_REGEX = Regex("""[._\-/\s]+""")

    /**
     * Extracts all `${key}` or `${key:default}` property keys from a string
     * (including multiple placeholders and SpEL expressions like `#{'${my.list}'.split(',')}`).
     */
    fun extractPlaceholders(text: String): List<String> {
        if (!text.contains("\${")) return emptyList()
        return PLACEHOLDER_REGEX.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /**
     * Produces a flat lowercase alphanumeric canonical key for O(1) index lookup.
     */
    fun canonicalize(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return ""
        val withIndices = ARRAY_INDEX_REGEX.replace(trimmed, "$1")
        val sb = StringBuilder(withIndices.length)
        for (ch in withIndices) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * Splits a key into normalized lowercase tokens along `.`, `_`, `-`, `[idx]`, and camelCase boundaries.
     */
    fun tokenize(rawKey: String): List<String> {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return emptyList()
        val withIndices = ARRAY_INDEX_REGEX.replace(trimmed, ".$1.")
        val rawParts = SEPARATOR_REGEX.split(withIndices).filter { it.isNotEmpty() }
        val tokens = mutableListOf<String>()
        for (part in rawParts) {
            tokens.addAll(splitCamelCase(part))
        }
        return tokens
    }

    /**
     * Checks whether a ConfigMap key and a Spring Boot key match under Spring relaxed binding.
     */
    fun matches(configMapKey: String, springKey: String): Boolean {
        val c1 = canonicalize(configMapKey)
        val c2 = canonicalize(springKey)
        if (c1.isEmpty() || c1 != c2) return false

        val t1 = tokenize(configMapKey)
        val t2 = tokenize(springKey)
        if (t1 == t2) return true

        // Allow cases where one side has merged words/acronyms (e.g. MY_HTTPURL vs my.http.url or MY_APP_URL vs my.appUrl)
        // as long as one token list can be formed by merging adjacent tokens of the other (already guaranteed when canonical forms match),
        // EXCEPT avoid matching single-word keys against completely unrelated multi-segment keys unless env-style.
        return isEnvStyle(configMapKey) || isEnvStyle(springKey) || t1.size == t2.size
    }

    private fun isEnvStyle(key: String): Boolean {
        val hasLetter = key.any { it.isLetter() }
        return hasLetter && key.all { !it.isLetter() || it.isUpperCase() }
    }

    private fun splitCamelCase(part: String): List<String> {
        if (part.all { !it.isLetter() || it.isUpperCase() } || part.all { !it.isLetter() || it.isLowerCase() }) {
            return listOf(part.lowercase())
        }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (i in part.indices) {
            val ch = part[i]
            val prev = if (i > 0) part[i - 1] else null
            val next = if (i + 1 < part.length) part[i + 1] else null
            val boundary = when {
                prev == null -> false
                ch.isUpperCase() && prev.isLowerCase() -> true
                ch.isUpperCase() && prev.isUpperCase() && next != null && next.isLowerCase() -> true
                ch.isDigit() != prev.isDigit() -> true
                else -> false
            }
            if (boundary && current.isNotEmpty()) {
                result.add(current.toString().lowercase())
                current.setLength(0)
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().lowercase())
        }
        return result
    }
}
