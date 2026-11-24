package com.jamilxt.k8sconfigmap.navigation

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.UsageIndexService
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.nio.file.Files

class ConfigMapNavigationIntegrationTest : LightJavaCodeInsightFixtureTestCase() {

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
                String getRequiredProperty(String key);
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.springframework.context.annotation;
            public @interface Bean {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.springframework.context.annotation;
            public @interface Configuration {}
            """.trimIndent()
        )
    }

    fun testConfigMapToSpringNavigationAndFindUsages() {
        val configMapFile = myFixture.configureByText(
            "configmap.yaml",
            """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: my-app
            spec:
              replicas: 1
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: order-service-config
              namespace: prod
            data:
              MY_APP_CONNECT_TIMEOUT: "5000"
              SPRING_DATASOURCE_URL: "jdbc:postgresql://db:5432/orders"
              API_SECRET_KEY: "secret-123"
              UNUSED_KEY: "nobody-uses-me"
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "src/OrderService.java",
            """
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.core.env.Environment;

            public class OrderService {
                @Value("${'$'}{my.app.connect-timeout:1000}")
                private int timeout;

                public String load(Environment env) {
                    String a = env.getProperty("api.secret.key");
                    String b = System.getenv("API_SECRET_KEY");
                    return a + b;
                }
            }
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "src/AppProperties.java",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties;

            @ConfigurationProperties(prefix = "my.app")
            public class AppProperties {
                private int connectTimeout;
            }
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "src/resources/application.yml",
            """
            spring:
              datasource:
                url: ${'$'}{SPRING_DATASOURCE_URL:jdbc:h2:mem:test}
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "src/resources/application.properties",
            """
            my.app.connect-timeout=${'$'}{MY_APP_CONNECT_TIMEOUT:2000}
            """.trimIndent()
        )

        val cmIndex = ConfigMapIndexService.getInstance(project)
        val timeoutEntries = cmIndex.findEntriesByKey("my.app.connect-timeout")
        assertEquals(1, timeoutEntries.size)
        assertEquals("order-service-config", timeoutEntries.first().configMapName)
        assertEquals("prod", timeoutEntries.first().namespace)
        assertTrue(cmIndex.findEntriesByKey("replicas").isEmpty())

        val usageIndex = UsageIndexService.getInstance(project)
        val timeoutUsages = usageIndex.findUsagesForConfigMapKey("MY_APP_CONNECT_TIMEOUT")
        assertTrue("Expected at least 3 usages, got ${timeoutUsages.size}", timeoutUsages.size >= 3)

        val apiKeyUsages = usageIndex.findUsagesForConfigMapKey("API_SECRET_KEY")
        assertEquals(2, apiKeyUsages.size)

        val unusedUsages = usageIndex.findUsagesForConfigMapKey("UNUSED_KEY")
        assertTrue(unusedUsages.isEmpty())

        val yamlKeyValues = PsiTreeUtil.findChildrenOfType(configMapFile, YAMLKeyValue::class.java)
        val timeoutKv = yamlKeyValues.first { it.keyText == "MY_APP_CONNECT_TIMEOUT" }
        val refs = ReferencesSearch.search(timeoutKv).findAll()
        assertTrue("Expected ReferencesSearch to find usages, got ${refs.size}", refs.size >= 3)

        val handler = ConfigMapGotoDeclarationHandler()
        val gotoTargets = handler.getGotoDeclarationTargets(timeoutKv.key, timeoutKv.textOffset, myFixture.editor)
        assertNotNull(gotoTargets)
        assertTrue(gotoTargets!!.size >= 3)

        val gutters = myFixture.findAllGutters()
        assertTrue("Expected gutter icons on used ConfigMap keys", gutters.size >= 3)
    }

    fun testMultiLanguageNavigationNodePythonGoDotEnv() {
        val configMapFile = myFixture.configureByText(
            "polyglot-cm.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: polyglot-config
            data:
              REDIS_HOST: "redis.internal"
              STRIPE_API_KEY: "sk_live_xxx"
            """.trimIndent()
        )

        myFixture.addFileToProject("web/config.ts", "export const redis = process.env.REDIS_HOST;")
        myFixture.addFileToProject("worker/app.py", "import os\nhost = os.getenv('REDIS_HOST')\nkey = os.environ['STRIPE_API_KEY']")
        myFixture.addFileToProject("api/main.go", "package main\nimport \"os\"\nvar h = os.Getenv(\"REDIS_HOST\")")
        myFixture.addFileToProject(".env.local", "REDIS_HOST=localhost\n")

        val usageIndex = UsageIndexService.getInstance(project)
        val redisUsages = usageIndex.findUsagesForConfigMapKey("REDIS_HOST")
        assertEquals("Expected 4 usages across TS, Python, Go, .env", 4, redisUsages.size)

        val stripeUsages = usageIndex.findUsagesForConfigMapKey("STRIPE_API_KEY")
        assertEquals(1, stripeUsages.size)

        // Verify Find Usages from ConfigMap YAML finds all 4 polyglot usages
        val redisKv = PsiTreeUtil.findChildrenOfType(configMapFile, YAMLKeyValue::class.java)
            .first { it.keyText == "REDIS_HOST" }
        val refs = ReferencesSearch.search(redisKv).findAll()
        assertEquals(4, refs.size)

        // Verify reverse GotoDeclaration from Python file -> ConfigMap
        myFixture.configureByText("check.py", "import os\nk = os.getenv('STRIPE_<caret>API_KEY')")
        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)
        val targets = ConfigMapGotoDeclarationHandler().getGotoDeclarationTargets(elementAtCaret, myFixture.caretOffset, myFixture.editor)
        assertNotNull("Expected GotoDeclaration from Python os.getenv to ConfigMap", targets)
        assertEquals(1, targets!!.size)
        assertEquals("STRIPE_API_KEY", (targets.first() as YAMLKeyValue).keyText)
    }

    fun testSpringToConfigMapNavigation() {
        myFixture.addFileToProject(
            "k8s/configmap.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: payment-config
            data:
              PAYMENT_GATEWAY_URL: "https://pay.example.com"
            """.trimIndent()
        )

        myFixture.configureByText(
            "PaymentClient.java",
            """
            import org.springframework.beans.factory.annotation.Value;

            public class PaymentClient {
                @Value("${'$'}{payment.gate<caret>way.url}")
                private String gatewayUrl;
            }
            """.trimIndent()
        )

        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)
        val handler = ConfigMapGotoDeclarationHandler()
        val targets = handler.getGotoDeclarationTargets(elementAtCaret, myFixture.caretOffset, myFixture.editor)
        assertNotNull("Expected GotoDeclaration to resolve to ConfigMap key", targets)
        assertEquals(1, targets!!.size)
        assertTrue(targets.first() is YAMLKeyValue)
        assertEquals("PAYMENT_GATEWAY_URL", (targets.first() as YAMLKeyValue).keyText)

        val gutters = myFixture.findAllGutters()
        assertTrue("Expected gutter icon on @Value annotation", gutters.isNotEmpty())
    }

    fun testGotoDeclarationDoesNotHijackJavaDeclarationsForUsages() {
        myFixture.addFileToProject(
            "k8s/configmap.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: qms-config
            data:
              URL_QMS_EXPERT_LEVEL: "http://cockpit-queue/expert-level"
              MY_APP_CONNECT_TIMEOUT: "5000"
              API_SECRET_KEY: "secret-123"
            """.trimIndent()
        )

        myFixture.configureByText(
            "QMSConfig.java",
            """
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.core.env.Environment;

            public class QMSConfig {
                @Value("${'$'}{url.qms.expert.level}")
                private String expertLevelUrl;

                public String load(Environment env, @Value("${'$'}{url.qms.expert.level}") String paramUrl) {
                    return env.getProperty("api.secret.key") + expertLevelUrl + paramUrl;
                }
            }
            """.trimIndent()
        )

        val handler = ConfigMapGotoDeclarationHandler()
        val text = myFixture.file.text

        // 1. Caret on @Value string literal -> MUST resolve to ConfigMap
        val valueKeyOffset = text.indexOf("url.qms.expert.level") + 4
        val valueKeyTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(valueKeyOffset),
            valueKeyOffset,
            myFixture.editor
        )
        assertNotNull("Expected GotoDeclaration on @Value key literal", valueKeyTargets)
        assertEquals(1, valueKeyTargets!!.size)
        assertEquals("URL_QMS_EXPERT_LEVEL", (valueKeyTargets.first() as YAMLKeyValue).keyText)

        // 2. Caret on field identifier `expertLevelUrl` -> MUST return null so IDE Show Usages / Lombok works
        val fieldNameOffset = text.indexOf("expertLevelUrl") + 3
        val fieldNameTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(fieldNameOffset),
            fieldNameOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack field identifier", fieldNameTargets)

        // 3. Caret on field type `String` -> MUST return null so IDE navigates to java.lang.String
        val fieldTypeOffset = text.indexOf("String expertLevelUrl") + 2
        val fieldTypeTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(fieldTypeOffset),
            fieldTypeOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack field type", fieldTypeTargets)

        // 4. Caret on `@Value` annotation name -> MUST return null so IDE navigates to @Value class
        val annotationNameOffset = text.indexOf("@Value") + 2
        val annotationNameTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(annotationNameOffset),
            annotationNameOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack @Value annotation name", annotationNameTargets)

        // 5. Caret on parameter identifier `paramUrl` -> MUST return null so IDE Show Usages works
        val paramNameOffset = text.indexOf("paramUrl") + 2
        val paramNameTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(paramNameOffset),
            paramNameOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack parameter identifier", paramNameTargets)

        // 6. Caret on `getProperty` method name -> MUST return null; caret on `"api.secret.key"` -> resolves to ConfigMap
        val getPropMethodOffset = text.indexOf("getProperty") + 3
        val getPropMethodTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(getPropMethodOffset),
            getPropMethodOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack getProperty method name", getPropMethodTargets)

        val getPropArgOffset = text.indexOf("api.secret.key") + 4
        val getPropArgTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(getPropArgOffset),
            getPropArgOffset,
            myFixture.editor
        )
        assertNotNull("Expected GotoDeclaration on env.getProperty key argument", getPropArgTargets)
        assertEquals("API_SECRET_KEY", (getPropArgTargets!!.first() as YAMLKeyValue).keyText)

        // 7. Caret on @ConfigurationProperties field identifier -> MUST return null (gutter icon handles ConfigMap nav)
        myFixture.configureByText(
            "AppProperties.java",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties;

            @ConfigurationProperties(prefix = "my.app")
            public class AppProperties {
                private int connect<caret>Timeout;
            }
            """.trimIndent()
        )
        val cpFieldTargets = handler.getGotoDeclarationTargets(
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack @ConfigurationProperties field identifier", cpFieldTargets)
    }

    fun testFieldFindUsagesShowsBothCodeAndConfigMapEntries() {
        myFixture.addFileToProject(
            "k8s/configmap.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: qms-config
            data:
              URL_QMS_EXPERT_LEVEL: "http://cockpit-queue/expert-level"
              MY_APP_CONNECT_TIMEOUT: "5000"
            """.trimIndent()
        )

        val qmsFile = myFixture.configureByText(
            "QMSConfig.java",
            """
            import org.springframework.beans.factory.annotation.Value;

            public class QMSConfig {
                @Value("${'$'}{url.qms.expert.level}")
                private String expertLevelUrl;

                public String getExpertLevelUrl() {
                    return expertLevelUrl;
                }
            }
            """.trimIndent()
        )

        val expertField = PsiTreeUtil.findChildrenOfType(qmsFile, com.intellij.psi.PsiField::class.java)
            .first { it.name == "expertLevelUrl" }
        val fieldRefs = ReferencesSearch.search(expertField).findAll()
        assertTrue(
            "Expected code usage reference for expertLevelUrl",
            fieldRefs.any { it.element.containingFile.name == "QMSConfig.java" }
        )
        val cmRef = fieldRefs.firstOrNull { it.element is YAMLKeyValue }
        assertNotNull("Expected ConfigMap YAML reference alongside code usages", cmRef)
        assertEquals("URL_QMS_EXPERT_LEVEL", (cmRef!!.element as YAMLKeyValue).keyText)
        assertSame("Rename must be a no-op on ConfigMap reference", cmRef.element, cmRef.handleElementRename("renamedField"))

        val appPropsFile = myFixture.configureByText(
            "AppProperties.java",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties;

            @ConfigurationProperties(prefix = "my.app")
            public class AppProperties {
                private int connectTimeout;

                public int readTimeout() {
                    return connectTimeout;
                }
            }
            """.trimIndent()
        )
        val cpField = PsiTreeUtil.findChildrenOfType(appPropsFile, com.intellij.psi.PsiField::class.java)
            .first { it.name == "connectTimeout" }
        val cpRefs = ReferencesSearch.search(cpField).findAll()
        assertTrue(
            "Expected code usage reference for connectTimeout",
            cpRefs.any { it.element.containingFile.name == "AppProperties.java" }
        )
        assertTrue(
            "Expected ConfigMap YAML reference for @ConfigurationProperties field",
            cpRefs.any { (it.element as? YAMLKeyValue)?.keyText == "MY_APP_CONNECT_TIMEOUT" }
        )
    }

    fun testRenameConfigMapKeySucceedsWithoutCorruptingUsages() {
        val configMapFile = myFixture.configureByText(
            "configmap.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: rename-test-config
            data:
              MY_APP_CONNECT_TIMEOUT: "5000"
            """.trimIndent()
        )

        val javaFile = myFixture.addFileToProject(
            "src/OrderService.java",
            """
            import org.springframework.beans.factory.annotation.Value;

            public class OrderService {
                @Value("${'$'}{my.app.connect-timeout:1000}")
                private int timeout;
            }
            """.trimIndent()
        )

        val propsClassFile = myFixture.addFileToProject(
            "src/AppProperties.java",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties;

            @ConfigurationProperties(prefix = "my.app")
            public class AppProperties {
                private int connectTimeout;
            }
            """.trimIndent()
        )

        val timeoutKv = PsiTreeUtil.findChildrenOfType(configMapFile, YAMLKeyValue::class.java)
            .first { it.keyText == "MY_APP_CONNECT_TIMEOUT" }

        myFixture.renameElement(timeoutKv, "MY_APP_READ_TIMEOUT")

        assertTrue(
            "Expected ConfigMap key to be renamed to MY_APP_READ_TIMEOUT, got:\n${configMapFile.text}",
            configMapFile.text.contains("MY_APP_READ_TIMEOUT: \"5000\"")
        )
        assertTrue(
            "Expected @Value string in Java file to remain intact",
            javaFile.text.contains("@Value(\"\${my.app.connect-timeout:1000}\")")
        )
        assertTrue(
            "Expected @ConfigurationProperties field in Java file to remain intact",
            propsClassFile.text.contains("private int connectTimeout;")
        )
    }

    fun testPropertiesAndAppYamlKeysPreserveShowUsagesAndConfigMapValueNotHijacked() {
        val configMapFile = myFixture.configureByText(
            "configmap.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: app-config
            data:
              URL_QMS_EXPERT_LEVEL: "http://cockpit-queue/expert-level"
              SPRING_DATASOURCE_URL: "jdbc:postgresql://db:5432/orders"
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "src/QMSConfig.java",
            """
            import org.springframework.beans.factory.annotation.Value;

            public class QMSConfig {
                @Value("${'$'}{url.qms.expert.level}")
                private String expertLevelUrl;
            }
            """.trimIndent()
        )

        val handler = ConfigMapGotoDeclarationHandler()

        // #10: Ctrl+Click on ConfigMap key -> resolves usages; Ctrl+Click on ConfigMap value URL -> must return null
        val cmText = configMapFile.text
        val cmKeyOffset = cmText.indexOf("URL_QMS_EXPERT_LEVEL") + 4
        val cmKeyTargets = handler.getGotoDeclarationTargets(
            configMapFile.findElementAt(cmKeyOffset),
            cmKeyOffset,
            myFixture.editor
        )
        assertNotNull("Expected GotoDeclaration on ConfigMap key", cmKeyTargets)
        assertTrue(cmKeyTargets!!.isNotEmpty())

        val cmValueOffset = cmText.indexOf("http://cockpit-queue") + 5
        val cmValueTargets = handler.getGotoDeclarationTargets(
            configMapFile.findElementAt(cmValueOffset),
            cmValueOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack ConfigMap value (e.g. URL)", cmValueTargets)

        // #9a: application.properties key -> GotoDeclaration returns null (preserving Show Usages),
        //      placeholder in value -> GotoDeclaration resolves to ConfigMap,
        //      ReferencesSearch on Property -> includes ConfigMap YAMLKeyValue
        val propsFile = myFixture.configureByText(
            "application.properties",
            """
            url.qms.expert.level=${'$'}{URL_QMS_EXPERT_LEVEL:http://localhost}
            """.trimIndent()
        )
        val propsText = propsFile.text
        val propKeyOffset = propsText.indexOf("url.qms.expert.level") + 4
        val propKeyTargets = handler.getGotoDeclarationTargets(
            propsFile.findElementAt(propKeyOffset),
            propKeyOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack .properties key", propKeyTargets)

        val propPlaceholderOffset = propsText.indexOf("URL_QMS_EXPERT_LEVEL") + 4
        val propPlaceholderTargets = handler.getGotoDeclarationTargets(
            propsFile.findElementAt(propPlaceholderOffset),
            propPlaceholderOffset,
            myFixture.editor
        )
        assertNotNull("Expected GotoDeclaration on .properties placeholder value", propPlaceholderTargets)
        assertEquals("URL_QMS_EXPERT_LEVEL", (propPlaceholderTargets!!.first() as YAMLKeyValue).keyText)

        val propElement = PsiTreeUtil.findChildrenOfType(propsFile, com.intellij.lang.properties.psi.Property::class.java).first()
        val propRefs = ReferencesSearch.search(propElement).findAll()
        assertTrue(
            "Expected ReferencesSearch on .properties key to include ConfigMap entry",
            propRefs.any { (it.element as? YAMLKeyValue)?.keyText == "URL_QMS_EXPERT_LEVEL" }
        )

        // #9b: application.yml key -> GotoDeclaration returns null,
        //      placeholder in value -> GotoDeclaration resolves to ConfigMap,
        //      ReferencesSearch on YAMLKeyValue -> includes ConfigMap YAMLKeyValue
        val appYamlFile = myFixture.configureByText(
            "application.yml",
            """
            spring:
              datasource:
                url: ${'$'}{SPRING_DATASOURCE_URL:jdbc:h2:mem:test}
            """.trimIndent()
        )
        val yamlText = appYamlFile.text
        val yamlKeyOffset = yamlText.indexOf("url:") + 1
        val yamlKeyTargets = handler.getGotoDeclarationTargets(
            appYamlFile.findElementAt(yamlKeyOffset),
            yamlKeyOffset,
            myFixture.editor
        )
        assertNull("GotoDeclaration must NOT hijack application.yml key", yamlKeyTargets)

        val yamlPlaceholderOffset = yamlText.indexOf("SPRING_DATASOURCE_URL") + 4
        val yamlPlaceholderTargets = handler.getGotoDeclarationTargets(
            appYamlFile.findElementAt(yamlPlaceholderOffset),
            yamlPlaceholderOffset,
            myFixture.editor
        )
        assertNotNull("Expected GotoDeclaration on application.yml placeholder value", yamlPlaceholderTargets)
        assertEquals("SPRING_DATASOURCE_URL", (yamlPlaceholderTargets!!.first() as YAMLKeyValue).keyText)

        val urlKv = PsiTreeUtil.findChildrenOfType(appYamlFile, YAMLKeyValue::class.java).first { it.keyText == "url" }
        val yamlRefs = ReferencesSearch.search(urlKv).findAll()
        assertTrue(
            "Expected ReferencesSearch on application.yml key to include ConfigMap entry",
            yamlRefs.any { (it.element as? YAMLKeyValue)?.keyText == "SPRING_DATASOURCE_URL" }
        )
    }

    fun testExternalConfigMapDirectoryDiscovery() {
        val tempDir = Files.createTempDirectory("k8s-external-cm-test")
        try {
            val externalFile = tempDir.resolve("external-cm.yaml")
            Files.writeString(
                externalFile,
                """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: external-billing-config
                data:
                  BILLING_RETRY_COUNT: "3"
                """.trimIndent()
            )
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(externalFile)

            val settings = NavigatorSettings.getInstance(project)
            settings.externalConfigMapPaths = listOf(tempDir.toAbsolutePath().toString())

            val entries = ConfigMapIndexService.getInstance(project).findEntriesByKey("billing.retry.count")
            assertEquals(1, entries.size)
            assertEquals("external-billing-config", entries.first().configMapName)
            assertEquals("3", entries.first().valuePreview)
        } finally {
            NavigatorSettings.getInstance(project).externalConfigMapPaths = emptyList()
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testBeanMethodConfigurationPropertiesNavigation() {
        val configMapFile = myFixture.configureByText(
            "online-shop-cm.yaml",
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: online-shop-config
            data:
              ONLINE_SHOP_SHOPARU_TYPE: "Shoparu"
              ONLINE_SHOP_SHOPARU_URL: "https://shoparu.example.com"
              ONLINE_SHOP_GP_TYPE: "GP Online Shop"
              ONLINE_SHOP_GP_EXPIRY: "120"
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "src/OnlineServiceConfig.java",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            @ConfigurationProperties(prefix = "online.service")
            public class OnlineServiceConfig {
                private String type;
                private String key;
                private String url;
                private String channel;
                private Long expiry;
            }
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "src/OnlineServiceProperties.java",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            public class OnlineServiceProperties {
                @Bean
                @ConfigurationProperties(prefix = "online.shop.shoparu")
                public OnlineServiceConfig shoparuConfig() {
                    return new OnlineServiceConfig();
                }

                @Bean
                @ConfigurationProperties(prefix = "online.shop.gp")
                public OnlineServiceConfig gpShopConfig() {
                    return new OnlineServiceConfig();
                }
            }
            """.trimIndent()
        )

        // Direction 1: ConfigMap -> @Bean method & return type field
        val usageIndex = UsageIndexService.getInstance(project)
        val shoparuTypeUsages = usageIndex.findUsagesForConfigMapKey("ONLINE_SHOP_SHOPARU_TYPE")
        assertEquals("Expected bean method and field usages", 2, shoparuTypeUsages.size)

        val gpExpiryUsages = usageIndex.findUsagesForConfigMapKey("ONLINE_SHOP_GP_EXPIRY")
        assertEquals("Expected bean method and field usages", 2, gpExpiryUsages.size)

        val yamlKeyValues = PsiTreeUtil.findChildrenOfType(configMapFile, YAMLKeyValue::class.java)
        val shoparuTypeKv = yamlKeyValues.first { it.keyText == "ONLINE_SHOP_SHOPARU_TYPE" }
        val refs = ReferencesSearch.search(shoparuTypeKv).findAll()
        assertEquals(2, refs.size)

        val cmGutters = myFixture.findAllGutters()
        assertEquals("Expected gutter icons on all 4 ConfigMap keys", 4, cmGutters.size)

        // Direction 2a: @Bean @ConfigurationProperties method -> ConfigMap
        myFixture.configureByFile("src/OnlineServiceProperties.java")
        val beanGutters = myFixture.findAllGutters()
        assertTrue("Expected gutter icons on @Bean @ConfigurationProperties methods", beanGutters.size >= 2)

        // Direction 2b: Return type class fields -> ConfigMap
        myFixture.configureByFile("src/OnlineServiceConfig.java")
        val configClassGutters = myFixture.findAllGutters()
        // type (2 matches), url (1 match), expiry (1 match) => 3 fields with gutter icons
        assertTrue("Expected gutter icons on OnlineServiceConfig fields (type, url, expiry)", configClassGutters.size >= 3)
    }
}
