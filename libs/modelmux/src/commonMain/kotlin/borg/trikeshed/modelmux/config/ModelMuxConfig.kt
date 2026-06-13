package borg.trikeshed.modelmux.config

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Properties

/**
 * ModelMuxConfig — CCEK element for .env configuration management.
 * Loads .env.default, .env.example, .env.local, and environment variables
 * with clear precedence: env var > .env.local > .env > .env.default > .env.example
 */
class ModelMuxConfig(
    parentJob: CompletableJob? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<ModelMuxConfig>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    private val properties = Properties()
    private var loaded = false

    /** Precedence order: env var > .env.local > .env > .env.default > .env.example */
    private val configFiles = listOf(
        ".env.example",
        ".env.default",
        ".env",
        ".env.local"
    )

    /**
     * Load configuration from all sources.
     * Must be called before accessing any config values.
     */
    suspend fun load(configDir: File = File(".")): ModelMuxConfig {
        requireState(ElementState.CREATED)
        state = ElementState.OPEN

        // Load files in precedence order (later files override earlier)
        for (fileName in configFiles) {
            val file = File(configDir, fileName)
            if (file.exists()) {
                loadPropertiesFile(file)
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
        val value = getString(key) ?: error("Required config '$key' not found. Check .env files and environment variables.")
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
            error("ModelMuxConfig.load() must be called before accessing config values")
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
        println("=== ModelMux Configuration ===")
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
}

/**
 * Configuration keys as constants for type-safe access
 */
object ModelMuxConfigKeys {
    // Server
    const val PORT = "MODELMUX_PORT"
    const val BIND_ADDRESS = "MODELMUX_BIND_ADDRESS"
    const val REQUEST_TIMEOUT_SECS = "MODELMUX_REQUEST_TIMEOUT_SECS"
    const val MAX_RETRIES = "MODELMUX_MAX_RETRIES"
    const val ENABLE_STREAMING = "MODELMUX_ENABLE_STREAMING"
    const val ENABLE_CACHING = "MODELMUX_ENABLE_CACHING"

    // Models
    const val DEFAULT_MODEL = "MODELMUX_DEFAULT_MODEL"
    const val FALLBACK_MODEL = "MODELMUX_FALLBACK_MODEL"
    const val MAX_CONTEXT_WINDOW = "MODELMUX_MAX_CONTEXT_WINDOW"

    // Provider API Keys
    const val OPENAI_API_KEY = "OPENAI_API_KEY"
    const val OPENAI_BASE_URL = "OPENAI_BASE_URL"
    const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
    const val ANTHROPIC_BASE_URL = "ANTHROPIC_BASE_URL"
    const val KILO_API_KEY = "KILO_API_KEY"
    const val KILO_BASE_URL = "KILO_BASE_URL"
    const val KILOCODE_API_KEY = "KILOCODE_API_KEY"
    const val KILOAI_API_KEY = "KILOAI_API_KEY"
    const val MOONSHOT_API_KEY = "MOONSHOT_API_KEY"
    const val MOONSHOT_BASE_URL = "MOONSHOT_BASE_URL"
    const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"
    const val DEEPSEEK_BASE_URL = "DEEPSEEK_BASE_URL"
    const val GROQ_API_KEY = "GROQ_API_KEY"
    const val GROQ_BASE_URL = "GROQ_BASE_URL"
    const val NVIDIA_API_KEY = "NVIDIA_API_KEY"
    const val NVIDIA_BASE_URL = "NVIDIA_BASE_URL"
    const val OPENROUTER_API_KEY = "OPENROUTER_API_KEY"
    const val OPENROUTER_BASE_URL = "OPENROUTER_BASE_URL"
    const val CEREBRAS_API_KEY = "CEREBRAS_API_KEY"
    const val CEREBRAS_BASE_URL = "CEREBRAS_BASE_URL"
    const val XAI_API_KEY = "XAI_API_KEY"
    const val XAI_BASE_URL = "XAI_BASE_URL"
    const val GEMINI_API_KEY = "GEMINI_API_KEY"
    const val GEMINI_BASE_URL = "GEMINI_BASE_URL"
    const val PERPLEXITY_API_KEY = "PERPLEXITY_API_KEY"
    const val PERPLEXITY_BASE_URL = "PERPLEXITY_BASE_URL"
    const val ZENMUX_API_KEY = "ZENMUX_API_KEY"
    const val ZENMUX_BASE_URL = "ZENMUX_BASE_URL"
    const val OPencode_API_KEY = "OPENCODE_API_KEY"
    const val OPencode_BASE_URL = "OPENCODE_BASE_URL"

    // Optional features
    const val ENABLE_OLLAMA_OPENROUTER = "MODELMUX_ENABLE_OLLAMA_OPENROUTER"
    const val ENABLE_OPENROUTER_FALLBACK = "MODELMUX_ENABLE_OPENROUTER_FALLBACK"
    const val INCLUDE_OPENROUTER_MODELS = "MODELMUX_INCLUDE_OPENROUTER_MODELS"
    const val OPENROUTER_FREE_MODEL = "OPENROUTER_FREE_MODEL"
    const val HUGGINGFACE_API_KEY = "HUGGINGFACE_API_KEY"

    // Search
    const val BRAVE_SEARCH_API_KEY = "BRAVE_SEARCH_API_KEY"
    const val TAVILY_SEARCH_API_KEY = "TAVILY_SEARCH_API_KEY"

    // Logging
    const val RUST_LOG = "RUST_LOG"
}