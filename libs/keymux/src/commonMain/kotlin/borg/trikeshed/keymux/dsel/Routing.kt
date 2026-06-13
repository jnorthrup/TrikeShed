package borg.trikeshed.keymux.dsel

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

/**
 * A discovered/configured provider with its routing info.
 */
@Serializable
data class ProviderDef(
    val name: String,
    val baseUrl: String,
    val keyEnv: String,
)

/**
 * Model card from provider /models endpoint.
 */
@Serializable
data class ModelCard(
    val id: String,
    val provider: String,
    val name: String,
    val contextWindow: Long,
    val maxTokens: Long,
    val inputCostPerMillion: Double,
    val outputCostPerMillion: Double,
    val isFree: Boolean,
    val supportsStreaming: Boolean,
    val supportsTools: Boolean,
    val tags: List<String> = emptyList(),
)

/**
 * Model card store for caching and lookup.
 */
class ModelCardStore {
    private val cards = mutable.MutableMap<String, ModelCard>()

    fun populateFromCached(models: List<CachedModel>) {
        for (model in models) {
            val card = ModelCard(
                id = model.id,
                provider = model.provider,
                name = model.name,
                contextWindow = model.contextWindow,
                maxTokens = model.maxTokens,
                inputCostPerMillion = model.inputCostPerMillion,
                outputCostPerMillion = model.outputCostPerMillion,
                isFree = model.isFree,
                supportsStreaming = model.supportsStreaming,
                supportsTools = model.supportsTools,
                tags = if (model.isFree) listOf("free") else emptyList(),
            )
            cards[model.id] = card
        }
    }

    fun getCard(modelId: String): ModelCard? {
        return cards[modelId]
    }

    fun getAllCards(): List<ModelCard> {
        return cards.values.toList()
    }
}

/**
 * Cached model from provider draw-through.
 */
@Serializable
data class CachedModel(
    val id: String,
    val provider: String,
    val name: String,
    val contextWindow: Long,
    val maxTokens: Long,
    val inputCostPerMillion: Double,
    val outputCostPerMillion: Double,
    val isFree: Boolean,
    val supportsStreaming: Boolean,
    val supportsTools: Boolean,
    val cachedAt: Long,
    val expiresAt: Long? = null,
) {
    fun isExpired(): Boolean {
        return expiresAt?.let { it < Instant.now().epochSeconds } ?: false
    }
}

/**
 * Route a model ID to (provider_name, base_url, key_env_var).
 * Model IDs use "provider/model-name" convention.
 * Returns null if no provider can be resolved.
 */
fun route(model: String): Triple<String, String, String>? {
    val provider = model.split('/').firstOrNull() ?: model

    return when (provider.lowercase()) {
        "anthropic" -> Triple("anthropic", "https://api.anthropic.com/v1", "ANTHROPIC_API_KEY")
        "openai" -> Triple("openai", "https://api.openai.com/v1", "OPENAI_API_KEY")
        "google", "gemini" -> Triple("google", "https://generativelanguage.googleapis.com/v1beta/openai", "GOOGLE_API_KEY")
        "groq" -> Triple("groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY")
        "openrouter" -> Triple("openrouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY")
        "mistral" -> Triple("mistral", "https://api.mistral.ai/v1", "MISTRAL_API_KEY")
        "xai", "grok" -> Triple("xai", "https://api.x.ai/v1", "XAI_API_KEY")
        "cerebras" -> Triple("cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY")
        "ollama" -> Triple(
            "ollama",
            System.getenv("OLLAMA_HOST") ?: "http://localhost:11434/v1",
            ""
        )
        "lmstudio" -> Triple("lmstudio", "http://localhost:1234/v1", "")
        else -> null
    }
}

/**
 * True if the key looks like a real secret (non-empty, not a placeholder).
 */
fun isRealKey(key: String): Boolean {
    return !key.isBlank()
        && key != "***"
        && key != "YOUR_API_KEY"
        && !key.startsWith("sk-test")
}

/**
 * Return all providers that have an API key set in the environment.
 */
fun discoverProviders(): List<ProviderDef> {
    val candidates = listOf(
        "anthropic" to "https://api.anthropic.com/v1" to "ANTHROPIC_API_KEY",
        "openai" to "https://api.openai.com/v1" to "OPENAI_API_KEY",
        "google" to "https://generativelanguage.googleapis.com/v1beta/openai" to "GOOGLE_API_KEY",
        "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai" to "GEMINI_API_KEY",
        "deepseek" to "https://api.deepseek.com/v1" to "DEEPSEEK_API_KEY",
        "groq" to "https://api.groq.com/openai/v1" to "GROQ_API_KEY",
        "openrouter" to "https://openrouter.ai/api/v1" to "OPENROUTER_API_KEY",
        "mistral" to "https://api.mistral.ai/v1" to "MISTRAL_API_KEY",
        "xai" to "https://api.x.ai/v1" to "XAI_API_KEY",
        "grok" to "https://api.x.ai/v1" to "XAI_API_KEY",
        "cerebras" to "https://api.cerebras.ai/v1" to "CEREBRAS_API_KEY",
        "nvidia" to "https://integrate.api.nvidia.com/v1" to "NVIDIA_API_KEY",
        "perplexity" to "https://api.perplexity.ai" to "PERPLEXITY_API_KEY",
        "moonshot" to "https://api.moonshot.cn/v1" to "MOONSHOT_API_KEY",
        "moonshotai" to "https://api.moonshot.cn/v1" to "MOONSHOTAI_API_KEY",
        "kimi" to "https://api.moonshot.cn/v1" to "KIMI_API_KEY",
        "huggingface" to "https://api-inference.huggingface.co/v1" to "HUGGINGFACE_API_KEY",
        "arcee" to "https://api.arcee.ai/v1" to "ARCEE_API_KEY",
        "ollama" to "http://localhost:11434/v1" to "",
        "lmstudio" to "http://localhost:1234/v1" to "",
    )

    return candidates
        .filter { (_, _, keyEnv) -> keyEnv.isBlank() || isRealKey(System.getenv(keyEnv) ?: "") }
        .map { (name, url, keyEnv) -> ProviderDef(name, url, keyEnv) }
}

/**
 * Return provider info by name, or null if unknown.
 */
fun getProvider(name: String): ProviderDef? {
    val candidates = listOf(
        "anthropic" to "https://api.anthropic.com/v1" to "ANTHROPIC_API_KEY",
        "openai" to "https://api.openai.com/v1" to "OPENAI_API_KEY",
        "google" to "https://generativelanguage.googleapis.com/v1beta/openai" to "GOOGLE_API_KEY",
        "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai" to "GEMINI_API_KEY",
        "deepseek" to "https://api.deepseek.com/v1" to "DEEPSEEK_API_KEY",
        "groq" to "https://api.groq.com/openai/v1" to "GROQ_API_KEY",
        "openrouter" to "https://openrouter.ai/api/v1" to "OPENROUTER_API_KEY",
        "mistral" to "https://api.mistral.ai/v1" to "MISTRAL_API_KEY",
        "xai" to "https://api.x.ai/v1" to "XAI_API_KEY",
        "grok" to "https://api.x.ai/v1" to "XAI_API_KEY",
        "cerebras" to "https://api.cerebras.ai/v1" to "CEREBRAS_API_KEY",
        "nvidia" to "https://integrate.api.nvidia.com/v1" to "NVIDIA_API_KEY",
        "perplexity" to "https://api.perplexity.ai" to "PERPLEXITY_API_KEY",
        "moonshot" to "https://api.moonshot.cn/v1" to "MOONSHOT_API_KEY",
        "moonshotai" to "https://api.moonshot.cn/v1" to "MOONSHOTAI_API_KEY",
        "kimi" to "https://api.moonshot.cn/v1" to "KIMI_API_KEY",
        "huggingface" to "https://api-inference.huggingface.co/v1" to "HUGGINGFACE_API_KEY",
        "arcee" to "https://api.arcee.ai/v1" to "ARCEE_API_KEY",
        "ollama" to "http://localhost:11434/v1" to "",
        "lmstudio" to "http://localhost:1234/v1" to "",
    )

    return candidates.firstOrNull { it.first == name }?.let { (n, u, k) ->
        ProviderDef(n, u, k)
    }
}

/**
 * Track token usage for quota accounting.
 */
fun trackTokens(provider: String, tokens: Long): Result<Unit> {
    // In a real implementation, this would update a persistent ledger
    return Result.success(Unit)
}

/**
 * Return quota usage as (provider, used_tokens, remaining_tokens, confidence).
 * Currently returns best-effort estimates.
 */
fun allProviderQuotas(): List<Pair<String, Triple<Long, Long, Double>>> {
    return discoverProviders().map { p ->
        p.name to Triple(0L, Long.MAX_VALUE, 1.0)
    }
}
