package com.jamilxt.k8sconfigmap.core

import com.jamilxt.k8sconfigmap.model.UsageKind

data class PatternMatch(
    val key: String,
    val keyStartOffset: Int,
    val keyEndOffset: Int,
    val kind: UsageKind
)

/**
 * Language-agnostic patterns for detecting environment variable lookups and config placeholders
 * across Node.js/TypeScript, Python, Go, C#/.NET, Rust, PHP, Ruby, and .env / TOML / JSON files.
 */
object UniversalCodePatterns {

    private data class PatternRule(
        val regex: Regex,
        val kind: UsageKind,
        val keyGroup: Int = 1
    )

    private val CODE_RULES = listOf(
        // JS/TS: process.env.MY_KEY or import.meta.env.MY_KEY
        PatternRule(Regex("""(?:process\.env|import\.meta\.env)\.([A-Za-z_][A-Za-z0-9_]*)"""), UsageKind.ENV_VAR_CALL),
        // JS/TS: process.env["MY_KEY"] / process.env['MY_KEY'] / import.meta.env["MY_KEY"]
        PatternRule(Regex("""(?:process\.env|import\.meta\.env)\s*\[\s*["']([^"']+)["']\s*]"""), UsageKind.ENV_VAR_CALL),
        // Python: os.getenv("MY_KEY") / os.environ.get("MY_KEY")
        PatternRule(Regex("""\bos\.(?:getenv|environ\.get)\s*\(\s*["']([^"']+)["']"""), UsageKind.ENV_VAR_CALL),
        // Python: os.environ["MY_KEY"]
        PatternRule(Regex("""\bos\.environ\s*\[\s*["']([^"']+)["']\s*]"""), UsageKind.ENV_VAR_CALL),
        // Go: os.Getenv("MY_KEY") / os.LookupEnv("MY_KEY")
        PatternRule(Regex("""\bos\.(?:Getenv|LookupEnv)\s*\(\s*["`]([^"`]+)["`]"""), UsageKind.ENV_VAR_CALL),
        // Go Viper: viper.GetString("my.key") / viper.Get("my.key") / etc.
        PatternRule(Regex("""\bviper\.Get(?:String|Int|Bool|Float64|Duration|StringSlice)?\s*\(\s*["`]([^"`]+)["`]"""), UsageKind.ENVIRONMENT_API),
        // C# / .NET: Environment.GetEnvironmentVariable("MY_KEY")
        PatternRule(Regex("""\bEnvironment\.GetEnvironmentVariable\s*\(\s*"([^"]+)"\s*\)"""), UsageKind.ENV_VAR_CALL),
        // Rust: env::var("MY_KEY") / env::var_os("MY_KEY")
        PatternRule(Regex("""\benv::var(?:_os)?\s*\(\s*"([^"]+)"\s*\)"""), UsageKind.ENV_VAR_CALL),
        // PHP: getenv("MY_KEY") / $_ENV["MY_KEY"] / $_SERVER["MY_KEY"] (avoid matching os.getenv / System.getenv)
        PatternRule(Regex("""(?:(?<![.\w])getenv\s*\(\s*|\${'$'}_(?:ENV|SERVER)\s*\[\s*)["']([^"']+)["']"""), UsageKind.ENV_VAR_CALL),
        // Ruby: ENV["MY_KEY"] / ENV.fetch("MY_KEY")
        PatternRule(Regex("""\bENV(?:\s*\[\s*|\.fetch\s*\(\s*)["']([^"']+)["']"""), UsageKind.ENV_VAR_CALL)
    )

    private val DOTENV_KEY_REGEX = Regex("""^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=""", RegexOption.MULTILINE)
    private val PLACEHOLDER_REGEX = Regex("""\$\{([^}:]+)(?::[^}]*)?}""")

    val SUPPORTED_CODE_EXTENSIONS = setOf(
        "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "py", "go", "cs", "rs", "php", "rb"
    )

    val SUPPORTED_CONFIG_EXTENSIONS = setOf("toml", "json")

    fun isDotEnvFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower == ".env" || lower.startsWith(".env.") || lower.endsWith(".env")
    }

    fun findCodeMatches(text: String): List<PatternMatch> {
        val matches = mutableListOf<PatternMatch>()
        val seen = HashSet<Pair<String, Int>>()
        for (rule in CODE_RULES) {
            for (m in rule.regex.findAll(text)) {
                val group = m.groups[rule.keyGroup] ?: continue
                val key = group.value.trim()
                if (key.isNotEmpty() && seen.add(key to group.range.first)) {
                    matches.add(PatternMatch(key, group.range.first, group.range.last + 1, rule.kind))
                }
            }
        }
        return matches
    }

    fun findDotEnvMatches(text: String): List<PatternMatch> {
        val matches = mutableListOf<PatternMatch>()
        for (m in DOTENV_KEY_REGEX.findAll(text)) {
            val group = m.groups[1] ?: continue
            val key = group.value.trim()
            if (key.isNotEmpty()) {
                matches.add(PatternMatch(key, group.range.first, group.range.last + 1, UsageKind.DOTENV_KEY))
            }
        }
        matches.addAll(findPlaceholderMatches(text))
        return matches
    }

    fun findPlaceholderMatches(text: String): List<PatternMatch> {
        if (!text.contains("\${")) return emptyList()
        return PLACEHOLDER_REGEX.findAll(text).mapNotNull { m ->
            val group = m.groups[1] ?: return@mapNotNull null
            val key = group.value.trim()
            if (key.isEmpty()) null else PatternMatch(key, group.range.first, group.range.last + 1, UsageKind.CONFIG_PLACEHOLDER)
        }.toList()
    }
}
