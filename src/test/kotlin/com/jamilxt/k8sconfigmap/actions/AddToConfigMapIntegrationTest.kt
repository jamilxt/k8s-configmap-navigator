package com.jamilxt.k8sconfigmap.actions

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.UsageIndexService

class AddToConfigMapIntegrationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        addSpringStubAnnotations()
    }

    private fun addSpringStubAnnotations() {
        myFixture.addClass(
            """
            package org.springframework.beans.factory.annotation;
            public @interface Value {
                String value();
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.springframework.boot.context.properties;
            public @interface ConfigurationProperties {
                String value() default "";
                String prefix() default "";
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.springframework.core.env;
            public interface Environment {
                String getProperty(String key);
                String getProperty(String key, String defaultValue);
            }
            """.trimIndent()
        )
    }

    private fun checkActionEnabled(): Boolean {
        val action = AddToConfigMapAction()
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .build()
        val event = AnActionEvent.createEvent(
            action,
            dataContext,
            null,
            "EditorPopup",
            ActionUiKind.POPUP,
            null
        )
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    fun testActionEnabledOnJavaConfigVariablesAndHiddenElsewhere() {
        // 1. Caret directly on the Java field name annotated with @Value
        myFixture.configureByText(
            "OrderService.java",
            """
            import org.springframework.beans.factory.annotation.Value;

            public class OrderService {
                @Value("${'$'}{my.app.connect-timeout:3000}")
                private int time<caret>out;

                private int regularCounter = 0;
            }
            """.trimIndent()
        )
        assertTrue("Expected action enabled on @Value field name", checkActionEnabled())
        val el1 = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val usageIndex = UsageIndexService.getInstance(project)
        assertEquals(listOf("my.app.connect-timeout"), usageIndex.extractKeysAtElement(el1, myFixture.caretOffset))
        assertEquals("3000", usageIndex.extractDefaultValueAtElement(el1, myFixture.caretOffset))

        // 2. Caret on a regular non-config Java field -> must be hidden
        val regularOffset = myFixture.file.text.indexOf("regularCounter") + 2
        myFixture.editor.caretModel.moveToOffset(regularOffset)
        assertFalse("Expected action hidden on non-config field", checkActionEnabled())

        // 3. Caret on a @ConfigurationProperties field with default initializer
        myFixture.configureByText(
            "AppProperties.java",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties;

            @ConfigurationProperties(prefix = "my.app")
            public class AppProperties {
                private int retry<caret>Count = 5;
            }
            """.trimIndent()
        )
        assertTrue("Expected action enabled on @ConfigurationProperties field", checkActionEnabled())
        val el2 = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertEquals(listOf("my.app.retryCount"), usageIndex.extractKeysAtElement(el2, myFixture.caretOffset))
        assertEquals("5", usageIndex.extractDefaultValueAtElement(el2, myFixture.caretOffset))

        // 4. Caret inside a ConfigMap YAML file -> must be hidden
        myFixture.configureByText(
            "cm.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: app-cm
            data:
              MY_<caret>KEY: "val"
            """.trimIndent()
        )
        assertFalse("Expected action hidden inside ConfigMap YAML", checkActionEnabled())
    }

    fun testAddAndUpdateKeyInStandardAndMultiDocConfigMap() {
        myFixture.configureByText(
            "multi-doc.yaml",
            """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: order-service
            spec:
              replicas: 2
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: order-service-config
              namespace: prod
            data:
              EXISTING_KEY: "old"
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: order-service
            """.trimIndent()
        )

        val cmIndex = ConfigMapIndexService.getInstance(project)
        val targets = cmIndex.getAllConfigMaps()
        assertEquals(1, targets.size)
        val target = targets.first()
        assertEquals("prod/order-service-config", target.displayName())

        // Add a new key
        val addResult = ConfigMapYamlWriter.addOrUpdateKey(project, target, "MY_APP_CONNECT_TIMEOUT", "3000")
        assertEquals(ConfigMapYamlWriter.WriteStatus.ADDED, addResult.status)
        assertNotNull(addResult.keyValue)
        assertEquals("MY_APP_CONNECT_TIMEOUT", addResult.keyValue!!.keyText)

        val afterAdd = myFixture.file.text
        assertTrue(afterAdd.contains("  EXISTING_KEY: \"old\"\n  MY_APP_CONNECT_TIMEOUT: \"3000\"\n---"))

        // Verify ConfigMapIndexService immediately sees the newly added key via relaxed binding
        val entries = cmIndex.findEntriesByKey("my.app.connect-timeout")
        assertEquals(1, entries.size)
        assertEquals("3000", entries.first().valuePreview)

        // Update existing key
        val updatedTarget = cmIndex.getAllConfigMaps().first()
        val updateResult = ConfigMapYamlWriter.addOrUpdateKey(project, updatedTarget, "EXISTING_KEY", "new \"quoted\" val")
        assertEquals(ConfigMapYamlWriter.WriteStatus.UPDATED, updateResult.status)
        assertTrue(myFixture.file.text.contains("""  EXISTING_KEY: "new \"quoted\" val""""))
    }

    fun testAddKeyToEmptyOrMissingDataConfigMaps() {
        myFixture.addFileToProject(
            "k8s/empty-data.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: empty-data-cm
            data: {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "k8s/no-data.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: no-data-cm
            """.trimIndent()
        )

        val cmIndex = ConfigMapIndexService.getInstance(project)
        val targets = cmIndex.getAllConfigMaps().associateBy { it.name }
        assertEquals(2, targets.size)

        val r1 = ConfigMapYamlWriter.addOrUpdateKey(project, targets.getValue("empty-data-cm"), "NEW_KEY_1", "v1")
        assertEquals(ConfigMapYamlWriter.WriteStatus.ADDED, r1.status)
        assertEquals(1, cmIndex.findEntriesByKey("new.key.1").size)

        val r2 = ConfigMapYamlWriter.addOrUpdateKey(project, targets.getValue("no-data-cm"), "NEW_KEY_2", "v2")
        assertEquals(ConfigMapYamlWriter.WriteStatus.ADDED, r2.status)
        assertEquals(1, cmIndex.findEntriesByKey("new.key.2").size)
    }

    fun testAddGutterIconShownOnlyForMissingPropertiesAndSwitchesAfterAdd() {
        myFixture.addFileToProject(
            "k8s/app-cm.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: app-cm
            data:
              EXISTING_TIMEOUT: "1000"
            """.trimIndent()
        )

        myFixture.configureByText(
            "FeatureService.java",
            """
            import org.springframework.beans.factory.annotation.Value;

            public class FeatureService {
                @Value("${'$'}{existing.timeout:500}")
                private int existing<caret>Timeout;

                @Value("${'$'}{missing.feature.flag:true}")
                private boolean missingFlag;
            }
            """.trimIndent()
        )

        // Existing property -> context menu hidden (already exists in all ConfigMaps)
        assertFalse("Expected context menu hidden when property already exists in all ConfigMaps", checkActionEnabled())

        // Missing property -> context menu enabled
        val missingOffset = myFixture.file.text.indexOf("missingFlag") + 2
        myFixture.editor.caretModel.moveToOffset(missingOffset)
        assertTrue("Expected context menu enabled on missing property", checkActionEnabled())

        // Verify gutter icons: existing gets Navigate tooltip, missing gets Add '+' tooltip
        val gutters = myFixture.findAllGutters()
        val tooltips = gutters.mapNotNull { it.tooltipText }
        assertTrue(
            "Expected Navigate gutter icon for existing property, got: $tooltips",
            tooltips.any { it.startsWith("Navigate to K8s ConfigMap") && it.contains("EXISTING_TIMEOUT") }
        )
        assertTrue(
            "Expected Add '+' gutter icon for missing property, got: $tooltips",
            tooltips.any { it == "Add 'MISSING_FEATURE_FLAG' to K8s ConfigMap..." }
        )
        assertFalse(
            "Existing property must NOT show Add '+' gutter icon",
            tooltips.any { it.contains("Add 'EXISTING_TIMEOUT'") }
        )

        // Add the missing property to ConfigMap -> '+' icon should turn into Navigate icon
        val cmTarget = ConfigMapIndexService.getInstance(project).getAllConfigMaps().first()
        ConfigMapYamlWriter.addOrUpdateKey(project, cmTarget, "MISSING_FEATURE_FLAG", "true")

        val updatedTooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertFalse(
            "Expected Add '+' gutter icon to disappear after adding property, got: $updatedTooltips",
            updatedTooltips.any { it.startsWith("Add '") }
        )
        assertTrue(
            "Expected Navigate gutter icon for newly added property, got: $updatedTooltips",
            updatedTooltips.any { it.contains("MISSING_FEATURE_FLAG") && it.startsWith("Navigate to K8s ConfigMap") }
        )
    }
}
