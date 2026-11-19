import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.jamilxt.k8sconfigmap"
version = "1.0.1"

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
