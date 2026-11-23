package com.jamilxt.k8sconfigmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyKeyNormalizerTest {

    @Test
    fun `matches relaxed binding variants`() {
        assertTrue(PropertyKeyNormalizer.matches("MY_APP_CONNECT_TIMEOUT", "my.app.connect-timeout"))
        assertTrue(PropertyKeyNormalizer.matches("MY_APP_CONNECT_TIMEOUT", "my.app.connectTimeout"))
        assertTrue(PropertyKeyNormalizer.matches("MY_APP_CONNECT_TIMEOUT", "my.app.connect_timeout"))
        assertTrue(PropertyKeyNormalizer.matches("MY_APP_CONNECT_TIMEOUT", "MY_APP_CONNECT_TIMEOUT"))
        assertTrue(PropertyKeyNormalizer.matches("SPRING_DATASOURCE_URL", "spring.datasource.url"))
        assertTrue(PropertyKeyNormalizer.matches("MY_LIST_0_NAME", "my.list[0].name"))
        assertTrue(PropertyKeyNormalizer.matches("MY_HTTP_URL", "my.httpUrl"))
    }

    @Test
    fun `rejects non-matching keys`() {
        assertFalse(PropertyKeyNormalizer.matches("MY_APP_TIMEOUT", "my.app.connect-timeout"))
        assertFalse(PropertyKeyNormalizer.matches("SPRING_DATASOURCE_URL", "spring.datasource.username"))
        assertFalse(PropertyKeyNormalizer.matches("", "my.app.timeout"))
        assertFalse(PropertyKeyNormalizer.matches("MY_APP_TIMEOUT", ""))
    }

    @Test
    fun `extracts placeholders with and without defaults`() {
        assertEquals(
            listOf("my.app.timeout"),
            PropertyKeyNormalizer.extractPlaceholders("\${my.app.timeout}")
        )
        assertEquals(
            listOf("MY_APP_TIMEOUT"),
            PropertyKeyNormalizer.extractPlaceholders("\${MY_APP_TIMEOUT:3000}")
        )
        assertEquals(
            listOf("my.host", "my.port"),
            PropertyKeyNormalizer.extractPlaceholders("https://\${my.host:localhost}:\${my.port:8080}/api")
        )
        assertEquals(
            listOf("my.list"),
            PropertyKeyNormalizer.extractPlaceholders("#{'\${my.list}'.split(',')}")
        )
        assertEquals(
            emptyList<String>(),
            PropertyKeyNormalizer.extractPlaceholders("plain-literal-value")
        )
    }

    @Test
    fun `tokenizes camelCase kebab-case dot and snake_case consistently`() {
        val expected = listOf("my", "app", "connect", "timeout")
        assertEquals(expected, PropertyKeyNormalizer.tokenize("MY_APP_CONNECT_TIMEOUT"))
        assertEquals(expected, PropertyKeyNormalizer.tokenize("my.app.connect-timeout"))
        assertEquals(expected, PropertyKeyNormalizer.tokenize("my.app.connectTimeout"))
        assertEquals(expected, PropertyKeyNormalizer.tokenize("my.app.connect_timeout"))
    }

    @Test
    fun `converts property keys to UPPER_SNAKE_CASE env var keys`() {
        assertEquals("MY_APP_CONNECT_TIMEOUT", PropertyKeyNormalizer.toEnvVarKey("my.app.connect-timeout"))
        assertEquals("MY_APP_CONNECT_TIMEOUT", PropertyKeyNormalizer.toEnvVarKey("my.app.connectTimeout"))
        assertEquals("MY_LIST_0_NAME", PropertyKeyNormalizer.toEnvVarKey("my.list[0].name"))
        assertEquals("API_SECRET_KEY", PropertyKeyNormalizer.toEnvVarKey("API_SECRET_KEY"))
    }

    @Test
    fun `extracts placeholder keys along with default values`() {
        assertEquals(
            listOf("my.app.timeout" to "3000"),
            PropertyKeyNormalizer.extractPlaceholdersWithDefaults("\${my.app.timeout:3000}")
        )
        assertEquals(
            listOf("my.app.url" to "https://example.com:8443/api"),
            PropertyKeyNormalizer.extractPlaceholdersWithDefaults("\${my.app.url:https://example.com:8443/api}")
        )
        assertEquals(
            listOf("my.app.key" to null),
            PropertyKeyNormalizer.extractPlaceholdersWithDefaults("\${my.app.key}")
        )
    }
}
