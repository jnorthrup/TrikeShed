package borg.literbike.modelmux

import kotlinx.serialization.Serializable

/**
 * Model Registry for ModelMux.
 * Ported from literbike/src/modelmux/registry.rs.
 */

/**
 * Provider configuration.
 */
@Serializable
data class ProviderEntry(
    val name: String,
    val displayName: String,
    val baseUrl: String,
    val apiKeyEnv: String,
    val authHeader: String = "Authorization",
    val authPrefix: String? = "Bearer",
    val isOpenAiCompatible: Boolean = true,
    val supportsStreaming: Boolean = true,
    val defaultTimeoutSecs: Long = 120
) {
    companion object {
        fun new(name: String, baseUrl: String, apiKeyEnv: String): ProviderEntry {
            return ProviderEntry(
                name = name,
                displayName = name,
                baseUrl = baseUrl,
                apiKeyEnv = apiKeyEnv,
                authHeader = "Authorization",
                authPrefix = "Bearer",
                isOpenAiCompatible = true,
                supportsStreaming = true,
                defaultTimeoutSecs = 120
            )
        }
    }

    fun withAuth(header: String, prefix: String?): ProviderEntry =
        copy(authHeader = header, authPrefix = prefix)

    fun withCompatibility(openai: Boolean): ProviderEntry =
        copy(isOpenAiCompatible = openai)

    fun withoutAuth(): ProviderEntry =
        copy(apiKeyEnv = "", authHeader = "", authPrefix = null)
}

/**
 * Model entry in registry.
 */
@Serializable
data class ModelEntry(
    val id: String,
    val provider: String,
    val aliases: MutableList<String> = mutableListOf(),
    var enabled: Boolean = true,
    var priority: Int = 50
) {
    companion object {
        fun new(id: String, provider: String): ModelEntry {
            return ModelEntry(id, provider)
        }
    }

    fun withAlias(alias: String): ModelEntry {
        aliases.add(alias)
        return this
    }

    fun withPriority(priority: Int): ModelEntry {
        this.priority = priority
        return this
    }
}

/**
 * Model registry managing providers and models.
 */
class ModelRegistry {
    private val providers = mutableMapOf<String, ProviderEntry>()
    private val models = mutableMapOf<String, ModelEntry>()
    private val modelAliases = mutableMapOf<String, String>()

    init {
        registerBuiltinProviders()
    }

    private fun registerBuiltinProviders() {
        // Local providers (no key needed)
        for (name in listOf("ollama", "lmstudio")) {
            val entry = ProviderEntry.new(name, "http://localhost:11434", "")
                .withoutAuth()
                .withCompatibility(true)
            providers[name] = entry
        }

        // Cloud providers
        val cloudProviders = listOf(
            "anthropic" to "https://api.anthropic.com",
            "openai" to "https://api.openai.com",
            "google" to "https://generativelanguage.googleapis.com",
            "groq" to "https://api.groq.com",
            "openrouter" to "https://openrouter.ai",
            "mistral" to "https://api.mistral.ai",
            "xai" to "https://api.x.ai",
            "cerebras" to "https://api.cerebras.ai"
        )

        for ((name, baseUrl) in cloudProviders) {
            val envKey = "${name.uppercase()}_API_KEY"
            val entry = if (name == "anthropic") {
                ProviderEntry.new(name, baseUrl, envKey)
                    .withAuth("x-api-key", null)
                    .withCompatibility(false)
            } else {
                ProviderEntry.new(name, baseUrl, envKey)
            }
            providers[name] = entry
        }
    }

    fun registerProvider(provider: ProviderEntry) {
        providers[provider.name] = provider
    }

    fun registerModel(model: ModelEntry) {
        val id = model.id
        for (alias in model.aliases) {
            modelAliases[alias] = id
        }
        models[id] = model
    }

    fun resolveModel(modelId: String): String {
        return modelAliases[modelId] ?: modelId
    }

    fun getProvider(name: String): ProviderEntry? = providers[name]

    fun getAllProviders(): List<ProviderEntry> = providers.values.toList()

    fun getEnabledProviders(): List<ProviderEntry> {
        return providers.values.filter { p ->
            p.apiKeyEnv.isEmpty() || System.getenv(p.apiKeyEnv) != null
        }
    }

    fun getModel(modelId: String): ModelEntry? {
        val resolved = resolveModel(modelId)
        return models[resolved]
    }

    fun getAllModels(): List<ModelEntry> = models.values.filter { it.enabled }

    fun getProviderModels(provider: String): List<ModelEntry> {
        return models.values.filter { it.provider == provider && it.enabled }
    }

    fun importFromCache(models: List<CachedModel>) {
        for (model in models) {
            val entry = ModelEntry.new(model.id, model.provider)
            registerModel(entry)
        }
    }

    fun deregisterModel(modelId: String) {
        val resolved = resolveModel(modelId)
        models.remove(resolved)?.let { entry ->
            for (alias in entry.aliases) {
                modelAliases.remove(alias)
            }
        }
        modelAliases.remove(modelId)
    }
}
