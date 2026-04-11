package borg.literbike.bin

/**
 * ModelMux - Desktop AI model proxy.
 * Routes to real providers based on API keys from environment.
 * Ported from literbike/src/bin/modelmux.rs.
 */

/**
 * Configuration for the model proxy.
 */
data class ProxyConfig(
    val bindAddress: String = "127.0.0.1",
    val port: Int = System.getenv("MODELMUX_PORT")?.toIntOrNull() ?: 8888,
    val enableStreaming: Boolean = true,
    val enableCaching: Boolean = true,
    val defaultModel: String? = System.getenv("MODELMUX_DEFAULT_MODEL"),
    val fallbackModel: String? = System.getenv("MODELMUX_FALLBACK_MODEL") ?: detectFallbackModel(),
    val requestTimeoutSecs: Int = 120,
    val maxRetries: Int = 2
)

/**
 * Auto-select first available key as fallback.
 */
private fun detectFallbackModel(): String? {
    val fallbacks = listOf(
        "ANTHROPIC_API_KEY" to "anthropic/claude-haiku-4-5-20251001",
        "OPENAI_API_KEY" to "openai/gpt-4o-mini",
        "GROQ_API_KEY" to "groq/llama-3.1-8b-instant",
        "OPENROUTER_API_KEY" to "openrouter/meta-llama/llama-3.1-8b-instruct:free"
    )
    return fallbacks.firstOrNull { (key, _) -> System.getenv(key) != null }?.second
}

/**
 * Provider configuration.
 */
data class ProviderConfig(
    val envVar: String,
    val providerName: String
) {
    val apiKey: String? get() = System.getenv(envVar)
    val isActive: Boolean get() = apiKey != null
}

/**
 * Model Proxy state.
 */
class ModelProxy(
    private val config: ProxyConfig
) {
    companion object {
        private val PROVIDER_KEYS = listOf(
            ProviderConfig("ANTHROPIC_API_KEY", "anthropic"),
            ProviderConfig("OPENAI_API_KEY", "openai"),
            ProviderConfig("GOOGLE_API_KEY", "google"),
            ProviderConfig("GROQ_API_KEY", "groq"),
            ProviderConfig("OPENROUTER_API_KEY", "openrouter"),
            ProviderConfig("MISTRAL_API_KEY", "mistral"),
            ProviderConfig("XAI_API_KEY", "xai"),
            ProviderConfig("CEREBRAS_API_KEY", "cerebras")
        )
    }

    private val activeProviders: List<String> = PROVIDER_KEYS
        .filter { it.isActive }
        .map { it.providerName }

    /** Initialize from environment */
    fun initFromEnv(): Result<Unit> {
        if (activeProviders.isEmpty()) {
            println("Warning: No API keys configured. Set environment variables for providers.")
        }
        return Result.success(Unit)
    }

    /** Log which providers are live */
    fun printStartupInfo() {
        println("modelmux starting on http://${config.bindAddress}:${config.port}")
        val providerStr = if (activeProviders.isEmpty()) {
            "none (set API key env vars)"
        } else {
            activeProviders.joinToString(", ")
        }
        println("active providers: $providerStr")
        println("opencode literbike provider: http://${config.bindAddress}:${config.port}")
    }

    /** Start the proxy server */
    fun startServer(): Result<Unit> {
        initFromEnv().getOrThrow()
        printStartupInfo()
        // In a full impl, would start HTTP server here
        println("ModelMux server ready (mock)")
        return Result.success(Unit)
    }
}

/**
 * Main entry point for ModelMux.
 */
fun runModelMux(config: ProxyConfig = ProxyConfig()) {
    val proxy = ModelProxy(config)
    proxy.startServer().fold(
        onSuccess = { println("ModelMux started successfully") },
        onFailure = { e ->
            println("ModelMux server error: ${e.message}")
        }
    )
}
