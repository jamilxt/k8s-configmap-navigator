# Changelog

All notable changes to **K8s ConfigMap Navigator** will be documented in this file.

## [1.0.2] - 2026-11-23
### Added
- **Add to K8s ConfigMap (Context Menu & `+` Gutter Icon):** Right-click on any config variable (including `@Value`, `@ConfigurationProperties`, `@ConfigProperty`, `Environment.getProperty`, `System.getenv`, etc.) or click the muted blue `+` gutter icon on missing properties to add them directly to one or more Kubernetes `ConfigMap` YAML files with automatic `UPPER_SNAKE_CASE` conversion, default value pre-fill, multi-ConfigMap selection, and full `Ctrl+Z` undo support.
- Support for method-level `@ConfigurationProperties` on `@Bean` factory methods: bidirectional gutter icons, `Ctrl+Click` Go to Declaration, and `Alt+F7` Find Usages between Kubernetes `ConfigMap` keys, `@Bean` methods, and the returned configuration class fields (including nested classes and compiled/setter-based types).

## [1.0.1] - 2026-11-19
### Fixed
- Removed unused `org.jetbrains.kotlin` plugin dependency from `plugin.xml` (Kotlin `.kt` files continue to work via UAST) to eliminate JetBrains Marketplace descriptor warnings.
- Replaced deprecated `DaemonCodeAnalyzer.restart()` with `FileContentUtilCore.reparseFiles(...)` for full IntelliJ 2024.3–2025.3+ compatibility.

### Added
- Privacy policy (`PRIVACY.md`), Marketplace intro banner, and screenshots in `README.md`.
- Automated GitHub Releases with plugin ZIP attachment on `v*` tags.

## [1.0.0] - 2026-11-19
### Added
- Bidirectional navigation (gutter icons & `Ctrl+Click`) and native Find Usages (`Alt+F7`) between Kubernetes `ConfigMap` YAML keys and application code.
- Multi-language & multi-IDE support: Spring Boot (`@Value`, `@ConfigurationProperties`, `Environment.getProperty`, `application*.yml`/`.properties`), Quarkus (`@ConfigProperty`), Micronaut, Node.js/TypeScript (`process.env`, `import.meta.env`), Python (`os.getenv`, `os.environ`), Go (`os.Getenv`, `os.LookupEnv`, `viper`), C#/.NET, Rust, PHP, Ruby, and `.env` / YAML / TOML / JSON configs.
- Spring Boot relaxed binding (`MY_APP_CONNECT_TIMEOUT` ↔ `my.app.connect-timeout`, `my.app.connectTimeout`, `my.list[0].name`).
- Configurable external ConfigMap and application project directories for multi-repo setups (**Settings → Tools → K8s ConfigMap Navigator**).
