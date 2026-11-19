# K8s ConfigMap Navigator

A universal JetBrains IDE plugin that helps developers **find usages** and **navigate bidirectionally** between Kubernetes `ConfigMap` YAML keys and application code across any stack.

Works on **IntelliJ IDEA (Community & Ultimate)**, **PyCharm**, **GoLand**, **WebStorm**, **Rider**, **PhpStorm**, and **RustRover** (2024.3+).

## Supported Ecosystems

- **Java & Kotlin (Spring Boot, Quarkus, Micronaut)**:
  - `@Value("${my.app.timeout:3000}")` (multiple placeholders & SpEL)
  - `@ConfigurationProperties(prefix = "my.app")` (classes, records, Kotlin data classes, nested classes)
  - `@ConfigProperty(name = "my.app.timeout")` (Quarkus / MicroProfile)
  - `Environment.getProperty(...)`, `System.getenv(...)`, `System.getProperty(...)`
  - `application*.yml` / `application*.properties` / `bootstrap*` (placeholders & direct property overrides)
- **Node.js / TypeScript / JavaScript**: `process.env.KEY`, `process.env["KEY"]`, `import.meta.env.KEY`
- **Python**: `os.getenv("KEY")`, `os.environ["KEY"]`, `os.environ.get("KEY")`
- **Go**: `os.Getenv("KEY")`, `os.LookupEnv("KEY")`, `viper.GetString("my.key")`
- **C# / .NET**: `Environment.GetEnvironmentVariable("KEY")`
- **Rust**: `env::var("KEY")`, `env::var_os("KEY")`
- **PHP**: `getenv("KEY")`, `$_ENV["KEY"]`, `$_SERVER["KEY"]`
- **Ruby**: `ENV["KEY"]`, `ENV.fetch("KEY")`
- **Config Files**: `.env` / `.env.*`, YAML, `.properties`, TOML, and JSON `${KEY}` placeholders

## Features

- **Relaxed Binding**: Automatically matches `UPPER_SNAKE_CASE` ConfigMap keys (`MY_APP_CONNECT_TIMEOUT`) with `kebab-case`, `camelCase`, `dot.notation`, `snake_case`, and array indices (`MY_LIST_0_NAME` ↔ `my.list[0].name`). Toggleable in settings.
- **Bidirectional Gutter Icons & `Ctrl+Click`**: Jump from ConfigMap keys to usages, or from code/config back to ConfigMap definitions (with namespace, name, and value preview).
- **Native Find Usages (`Alt+F7`)**: Invoke Find Usages directly on any `ConfigMap` `data:` key.
- **Monorepo & Multi-Repo**: Configure external Kubernetes ConfigMap or application directories under **Settings → Tools → K8s ConfigMap Navigator**.

## Development & Publishing

```bash
# Run unit & integration tests
./gradlew test

# Build installable plugin ZIP (build/distributions/)
./gradlew buildPlugin

# Launch sandboxed IDE with plugin installed
./gradlew runIde

# Publish to JetBrains Marketplace (requires PUBLISH_TOKEN env var)
./gradlew publishPlugin
```
