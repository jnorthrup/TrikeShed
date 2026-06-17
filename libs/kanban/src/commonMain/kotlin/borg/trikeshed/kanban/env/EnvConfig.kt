package borg.trikeshed.kanban.env

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CompletableJob
import java.io.File
import java.util.Properties

/**
 * EnvConfig — CCEK element for .env configuration management.
 * Loads ~/.hermes/.env + per-profile ~/.hermes/profiles/<name>/.env
 * with clear precedence: env var > profile .env > global .env > .env.default > .env.example
 */
class EnvConfig(
    parentJob: CompletableJob? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<EnvConfig>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    private val properties = Properties()
    private var loaded = false
    private var profileName: String? = null

    /** Precedence order: .env.example > .env.default > global .env > profile .env > env vars */
    private val globalConfigFiles = listOf(
        ".env.example",
        ".env.default",
        ".env",
    )

    private fun profileConfigFiles(profile: String): List<String> =
        listOf(".hermes/profiles/$profile/.env")

    /**
     * Load configuration from all sources.
     * Must be called before accessing any config values.
     * @param profile Optional profile name (e.g., "chimera", "kanban-worker")
     * @param configDir Base config directory (defaults to user home)
     */
    suspend fun load(
        profile: String? = null,
        configDir: File = File(System.getProperty("user.home"))
    ): EnvConfig {
        requireState(ElementState.CREATED)
        state = ElementState.OPEN
        profileName = profile

        // Load global config files in precedence order
        for (fileName in globalConfigFiles) {
            val file = File(configDir, ".hermes/$fileName")
            if (file.exists()) {
                loadPropertiesFile(file)
            }
        }

        // Load profile-specific config (overrides global)
        profile?.let { p ->
            for (fileName in profileConfigFiles(p)) {
                val file = File(configDir, fileName)
                if (file.exists()) {
                    loadPropertiesFile(file)
                }
            }
        }

        // Environment variables always win - load them last
        loadEnvironmentVariables()

        loaded = true
        state = ElementState.ACTIVE
        return this
    }

    private fun loadPropertiesFile(file: File) {
        val props = Properties()
        file.inputStream().use { input ->
            props.load(input)
        }
        // Merge into main properties (later wins)
        properties.putAll(props)
    }

    private fun loadEnvironmentVariables() {
        System.getenv().forEach { (key, value) ->
            properties[key] = value
        }
    }

    /** Get string value with optional default */
    fun getString(key: String, default: String? = null): String? {
        ensureLoaded()
        return properties.getProperty(key, default)
    }

    /** Get required string value (throws if missing) */
    fun getRequiredString(key: String): String {
        val value = getString(key) ?: error("Required config '$key' not found. Check ~/.hermes/.env and environment variables.")
        return value
    }

    /** Get int value with optional default */
    fun getInt(key: String, default: Int = 0): Int {
        return getString(key)?.toIntOrNull() ?: default
    }

    /** Get long value with optional default */
    fun getLong(key: String, default: Long = 0L): Long {
        return getString(key)?.toLongOrNull() ?: default
    }

    /** Get boolean value with optional default */
    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return getString(key)?.toBoolean() ?: default
    }

    /** Get list of strings (comma-separated) */
    fun getStringList(key: String): List<String> {
        return getString(key)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    }

    private fun ensureLoaded() {
        if (!loaded) {
            error("EnvConfig.load() must be called before accessing config values")
        }
    }

    /** Get all keys with a prefix */
    fun getKeysWithPrefix(prefix: String): Map<String, String> {
        ensureLoaded()
        return properties
            .stringPropertyNames()
            .filter { it.startsWith(prefix) }
            .associateWith { properties.getProperty(it)!! }
    }

    /** Check if a key exists and has a non-placeholder value */
    fun hasRealValue(key: String): Boolean {
        val value = getString(key) ?: return false
        return isRealValue(value)
    }

    /** Check if a string value is a real value (not placeholder) */
    private fun isRealValue(value: String): Boolean {
        val v = value.trim()
        if (v.length < 10) return false
        if (v.startsWith("your_") || v.startsWith("***")) return false
        if (v.contains("xxx", ignoreCase = true) || v.contains("here", ignoreCase = true) || v.contains("TODO", ignoreCase = true)) return false
        return true
    }

    /** Print all loaded config (sanitized - hides secrets) */
    fun printConfig() {
        ensureLoaded()
        println("=== EnvConfig ($profileName) ===")
        properties.stringPropertyNames().sorted().forEach { key ->
            val value = properties.getProperty(key)!!
            val displayValue = if (key.contains("KEY", ignoreCase = true) || key.contains("SECRET", ignoreCase = true) || key.contains("TOKEN", ignoreCase = true)) {
                if (value.length > 8) "${value.substring(0, 4)}...${value.substring(value.length - 4)}" else "****"
            } else {
                value
            }
            println("  $key=$displayValue")
        }
        println("==============================")
    }

    /** Get the active profile name */
    fun getProfile(): String? = profileName

    /** Get all provider API keys that have real values */
    fun getAvailableProviders(): Map<String, String> {
        ensureLoaded()
        return properties.stringPropertyNames()
            .filter { it.endsWith("_API_KEY") || it.endsWith("_AUTH_TOKEN") }
            .filter { hasRealValue(it) }
            .associateWith { properties.getProperty(it)!! }
    }

    /** Get modelmux-specific config */
    fun getModelMuxConfig(): ModelMuxEnvConfig {
        return ModelMuxEnvConfig(this)
    }
}

/** Type-safe access to modelmux-relevant env vars */
class ModelMuxEnvConfig(private val env: EnvConfig) {
    // Server
    val port: Int = env.getInt("MODELMUX_PORT", 8888)
    val bindAddress: String = env.getString("MODELMUX_BIND_ADDRESS", "0.0.0.0")!!
    val requestTimeoutSecs: Int = env.getInt("MODELMUX_REQUEST_TIMEOUT_SECS", 120)
    val maxRetries: Int = env.getInt("MODELMUX_MAX_RETRIES", 2)
    val enableStreaming: Boolean = env.getBoolean("MODELMUX_ENABLE_STREAMING", true)
    val enableCaching: Boolean = env.getBoolean("MODELMUX_ENABLE_CACHING", true)

    // Models
    val defaultModel: String? = env.getString("MODELMUX_DEFAULT_MODEL")
    val fallbackModel: String? = env.getString("MODELMUX_FALLBACK_MODEL")
    val maxContextWindow: Int = env.getInt("MODELMUX_MAX_CONTEXT_WINDOW", 128000)

    // Provider API Keys
    val openaiApiKey: String? = env.getString("OPENAI_API_KEY")
    val openaiBaseUrl: String? = env.getString("OPENAI_BASE_URL")
    val anthropicApiKey: String? = env.getString("ANTHROPIC_API_KEY")
    val anthropicBaseUrl: String? = env.getString("ANTHROPIC_BASE_URL")
    val googleApiKey: String? = env.getString("GOOGLE_API_KEY")
    val geminiApiKey: String? = env.getString("GEMINI_API_KEY")
    val geminiBaseUrl: String? = env.getString("GEMINI_BASE_URL")
    val groqApiKey: String? = env.getString("GROQ_API_KEY")
    val groqBaseUrl: String? = env.getString("GROQ_BASE_URL")
    val openrouterApiKey: String? = env.getString("OPENROUTER_API_KEY")
    val openrouterBaseUrl: String? = env.getString("OPENROUTER_BASE_URL")
    val nvidiaApiKey: String? = env.getString("NVIDIA_API_KEY")
    val nvidiaBaseUrl: String? = env.getString("NVIDIA_BASE_URL")
    val xaiApiKey: String? = env.getString("XAI_API_KEY")
    val xaiBaseUrl: String? = env.getString("XAI_BASE_URL")
    val cerebrasApiKey: String? = env.getString("CEREBRAS_API_KEY")
    val cerebrasBaseUrl: String? = env.getString("CEREBRAS_BASE_URL")
    val moonshotApiKey: String? = env.getString("MOONSHOT_API_KEY")
    val moonshotBaseUrl: String? = env.getString("MOONSHOT_BASE_URL")
    val deepseekApiKey: String? = env.getString("DEEPSEEK_API_KEY")
    val deepseekBaseUrl: String? = env.getString("DEEPSEEK_BASE_URL")
    val kiloApiKey: String? = env.getString("KILO_API_KEY")
    val kiloBaseUrl: String? = env.getString("KILO_BASE_URL")
    val kilocodeApiKey: String? = env.getString("KILOCODE_API_KEY")
    val kiloaiApiKey: String? = env.getString("KILOAI_API_KEY")
    val perplexityApiKey: String? = env.getString("PERPLEXITY_API_KEY")
    val perplexityBaseUrl: String? = env.getString("PERPLEXITY_BASE_URL")
    val zenmuxApiKey: String? = env.getString("ZENMUX_API_KEY")
    val zenmuxBaseUrl: String? = env.getString("ZENMUX_BASE_URL")
    val opencodeApiKey: String? = env.getString("OPENCODE_API_KEY")
    val opencodeBaseUrl: String? = env.getString("OPENCODE_BASE_URL")
    val huggingfaceApiKey: String? = env.getString("HUGGINGFACE_API_KEY")

    // Optional features
    val enableOllamaOpenRouter: Boolean = env.getBoolean("MODELMUX_ENABLE_OLLAMA_OPENROUTER", false)
    val enableOpenRouterFallback: Boolean = env.getBoolean("MODELMUX_ENABLE_OPENROUTER_FALLBACK", true)
    val includeOpenRouterModels: Boolean = env.getBoolean("MODELMUX_INCLUDE_OPENROUTER_MODELS", true)
    val openrouterFreeModel: String? = env.getString("OPENROUTER_FREE_MODEL")

    // Search
    val braveSearchApiKey: String? = env.getString("BRAVE_SEARCH_API_KEY")
    val tavilySearchApiKey: String? = env.getString("TAVILY_SEARCH_API_KEY")

    // Logging
    val rustLog: String? = env.getString("RUST_LOG")

    /** Get all enabled providers with their keys */
    fun getEnabledProviders(): List<ProviderConfig> {
        val providers = mutableListOf<ProviderConfig>()

        // Local providers (no key needed)
        providers += ProviderConfig("ollama", "http://localhost:11434/v1", "", true)
        providers += ProviderConfig("lmstudio", "http://localhost:1234/v1", "", true)

        // Cloud providers
        openaiApiKey?.let { providers += ProviderConfig("openai", openaiBaseUrl ?: "https://api.openai.com/v1", it, false) }
        anthropicApiKey?.let { providers += ProviderConfig("anthropic", anthropicBaseUrl ?: "https://api.anthropic.com", it, false) }
        googleApiKey?.let { providers += ProviderConfig("google", "https://generativelanguage.googleapis.com/v1beta/openai/", it, false) }
        geminiApiKey?.let { providers += ProviderConfig("google", geminiBaseUrl ?: "https://generativelanguage.googleapis.com/v1beta/openai/", it, false) }
        groqApiKey?.let { providers += ProviderConfig("groq", groqBaseUrl ?: "https://api.groq.com/openai/v1", it, false) }
        openrouterApiKey?.let { providers += ProviderConfig("openrouter", openrouterBaseUrl ?: "https://openrouter.ai/api/v1", it, false) }
        nvidiaApiKey?.let { providers += ProviderConfig("nvidia", nvidiaBaseUrl ?: "https://integrate.api.nvidia.com/v1", it, false) }
        xaiApiKey?.let { providers += ProviderConfig("xai", xaiBaseUrl ?: "https://api.x.ai/v1", it, false) }
        cerebrasApiKey?.let { providers += ProviderConfig("cerebras", cerebrasBaseUrl ?: "https://api.cerebras.ai/v1", it, false) }
        moonshotApiKey?.let { providers += ProviderConfig("moonshot", moonshotBaseUrl ?: "https://api.moonshot.ai/v1", it, false) }
        deepseekApiKey?.let { providers += ProviderConfig("deepseek", deepseekBaseUrl ?: "https://api.deepseek.com/v1", it, false) }
        kiloApiKey?.let { providers += ProviderConfig("kilo", kiloBaseUrl ?: "https://api.kilocode.ai/v1", it, false) }
        kilocodeApiKey?.let { providers += ProviderConfig("kilocode", "https://api.kilocode.ai/v1", it, false) }
        kiloaiApiKey?.let { providers += ProviderConfig("kiloai", "https://api.kiloai.com/v1", it, false) }
        perplexityApiKey?.let { providers += ProviderConfig("perplexity", perplexityBaseUrl ?: "https://api.perplexity.ai/v1", it, false) }
        zenmuxApiKey?.let { providers += ProviderConfig("zenmux", zenmuxBaseUrl ?: "https://api.zenmux.ai/v1", it, false) }
        opencodeApiKey?.let { providers += ProviderConfig("opencode", opencodeBaseUrl ?: "https://api.opencode.ai/v1", it, false) }
        huggingfaceApiKey?.let { providers += ProviderConfig("huggingface", "https://api-inference.huggingface.co/v1", it, false) }

        return providers.filter { it.hasKey || it.isLocal }
    }
}

data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val isLocal: Boolean = false
) {
    val hasKey: Boolean = apiKey.isNotBlank()
}