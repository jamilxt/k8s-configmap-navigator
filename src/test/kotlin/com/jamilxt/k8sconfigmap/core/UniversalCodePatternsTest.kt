package com.jamilxt.k8sconfigmap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UniversalCodePatternsTest {

    @Test
    fun `extracts env vars from JS TS Python Go CSharp Rust PHP Ruby`() {
        val sample = """
            const a = process.env.NODE_API_URL;
            const b = process.env["NODE_SECRET"];
            const c = import.meta.env.VITE_APP_TITLE;
            val1 = os.getenv("PY_DB_HOST")
            val2 = os.environ["PY_DB_PORT"]
            val3 = os.environ.get('PY_CACHE_URL')
            g1 := os.Getenv("GO_REDIS_ADDR")
            g2, _ := os.LookupEnv("GO_LOG_LEVEL")
            g3 := viper.GetString("go.service.name")
            var cs = Environment.GetEnvironmentVariable("DOTNET_CONN");
            let rs = env::var("RUST_WORKER_THREADS");
            ${'$'}p1 = getenv("PHP_QUEUE_NAME");
            ${'$'}p2 = ${'$'}_ENV['PHP_APP_ENV'];
            rb1 = ENV["RUBY_MAILER_HOST"]
            rb2 = ENV.fetch('RUBY_SECRET')
        """.trimIndent()

        val keys = UniversalCodePatterns.findCodeMatches(sample).map { it.key }.toSet()
        assertEquals(
            setOf(
                "NODE_API_URL", "NODE_SECRET", "VITE_APP_TITLE",
                "PY_DB_HOST", "PY_DB_PORT", "PY_CACHE_URL",
                "GO_REDIS_ADDR", "GO_LOG_LEVEL", "go.service.name",
                "DOTNET_CONN", "RUST_WORKER_THREADS",
                "PHP_QUEUE_NAME", "PHP_APP_ENV",
                "RUBY_MAILER_HOST", "RUBY_SECRET"
            ),
            keys
        )
    }

    @Test
    fun `extracts keys and placeholders from dotenv files`() {
        val sample = """
            # comment
            APP_PORT=8080
            export DB_URL=postgres://${'$'}{DB_HOST}:5432/app
        """.trimIndent()

        val keys = UniversalCodePatterns.findDotEnvMatches(sample).map { it.key }.toSet()
        assertEquals(setOf("APP_PORT", "DB_URL", "DB_HOST"), keys)
    }
}
