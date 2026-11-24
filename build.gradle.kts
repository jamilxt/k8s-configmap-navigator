import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.jamilxt.k8sconfigmap"
version = "1.0.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("com.intellij.properties")
        bundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.jamilxt.k8s-configmap-navigator"
        name = "K8s ConfigMap Navigator"
        version = project.version.toString()
        changeNotes = """
            <h3>1.0.3</h3>
            <ul>
                <li>Added <b>smart property-group placement</b> when adding keys via the <code>+</code> gutter icon or <b>Add to K8s ConfigMap...</b>, inserting new keys right after the matching prefix group while preserving indentation and inline comments.</li>
                <li>Fixed <code>Ctrl+Click</code> / <code>Cmd+Click</code> (Go to Declaration or Usages) on <code>@Value</code> and <code>@ConfigurationProperties</code> fields, parameters, methods, and types so IntelliJ's built-in <b>Show Usages</b> and Lombok getter/setter navigation are no longer intercepted.</li>
                <li>Fixed <code>Ctrl+Click</code> on property keys in <code>.properties</code> and <code>application.yml</code> files so Java/Spring usages are preserved alongside matching <code>ConfigMap</code> entries in <b>Show Usages</b> and <b>Find Usages</b> (<code>Alt+F7</code>).</li>
                <li>Fixed <b>Rename</b> (<code>Shift+F6</code>) on Kubernetes <code>ConfigMap</code> YAML keys failing after <i>"Looking for usages..."</i>, and restricted <code>Ctrl+Click</code> in ConfigMaps to keys only so URL values remain clickable.</li>
                <li>Included matching Kubernetes <code>ConfigMap</code> entries alongside code/Lombok usages in <b>Show Usages</b> and <b>Find Usages</b> (<code>Alt+F7</code>) on config-bound fields and parameters.</li>
            </ul>
            <h3>1.0.2</h3>
            <ul>
                <li>Added <b>Add to K8s ConfigMap...</b> right-click context menu action and <code>+</code> gutter icon on missing config variables, with automatic <code>UPPER_SNAKE_CASE</code> conversion, default value pre-fill, multi-ConfigMap selection, and full Undo support.</li>
                <li>Added support for method-level <code>@ConfigurationProperties</code> on <code>@Bean</code> factory methods, including bidirectional gutter icons, Go to Declaration, Find Usages, and field-level resolution on the returned config class.</li>
            </ul>
            <h3>1.0.1</h3>
            <ul>
                <li>Removed unused Kotlin plugin dependency and replaced deprecated <code>DaemonCodeAnalyzer.restart()</code> for full IntelliJ 2024.3–2025.3+ compatibility.</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
                <li>Initial release of K8s ConfigMap Navigator.</li>
                <li>Bidirectional navigation (gutter icons &amp; Ctrl+Click) and native Find Usages (Alt+F7) from Kubernetes ConfigMap keys.</li>
                <li>Multi-language &amp; multi-IDE support: Spring Boot (Java &amp; Kotlin), Quarkus, Micronaut, Node.js/TypeScript, Python, Go, C#/.NET, Rust, PHP, Ruby, and .env / YAML / Properties / TOML / JSON configs.</li>
                <li>Relaxed binding matching and configurable external ConfigMap / application paths for multi-repo setups.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "243"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    buildSearchableOptions = false
}

tasks {
    test {
        useJUnit()
    }
}
