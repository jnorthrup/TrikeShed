package borg.literbike.ccek.keymux

/**
 * Type definitions for the Universal Model Facade
 */

/**
 * Model information for /v1/models
 */
data class ModelInfo(
    val id: String,
    val object: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    val ownedBy: String,
    val metadata: WebModelCard? = null
)

/**
 * Specialized agent metadata ("Web Model Card")
 */
data class WebModelCard(
    val tags: List<String> = emptyList(),
    val contextWindow: ULong = 0uL,
    val pricing: Pricing? = null,
    val reasoningDepth: UByte = 0u, // 1-10
    val codeNative: Boolean = false
)

/**
 * Model pricing info
 */
data class Pricing(
    val prompt: Double,
    val completion: Double,
    val unit: String // e.g. "1M tokens"
)

/**
 * Model identifier parsed from "/provider/model" syntax
 */
data class ModelId(
    val provider: String,
    val model: String
) {
    companion object {
        fun parse(s: String): ModelId? {
            val parts = s.split('/', limit = 2)
            if (parts.size != 2) return null
            val provider = parts[0].trim()
            val model = parts[1].trim()
            if (provider.isEmpty() || model.isEmpty()) return null
            return ModelId(provider, model)
        }
    }

    override fun toString(): String = "$provider/$model"
}

/**
 * Provider definition
 */
data class ProviderDef(
    val name: String,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    val isApiKeyValid: Boolean = false,
    val quotaRemaining: Double = 0.0,
    val priority: Int = 0
)

/**
 * Provider selection rule
 */
sealed class ProviderSelectionRule {
    data class Fallback(val providers: List<String>) : ProviderSelectionRule()
    data class RoundRobin(val providers: List<String>) : ProviderSelectionRule()
    data class CostOptimized(val providers: List<String>) : ProviderSelectionRule()
    data class QualityFirst(val providers: List<String>) : ProviderSelectionRule()
    data class Fixed(val provider: String) : ProviderSelectionRule()
}

/**
 * Provider potential score
 */
data class ProviderPotential(
    val provider: String,
    val score: Double,
    val available: Boolean,
    val latencyMs: Long = 0L
)

/**
 * Quota container
 */
data class QuotaContainer(
    val provider: String,
    val total: Double = 0.0,
    val used: Double = 0.0,
    val remaining: Double = 0.0,
    val resetAt: Long = 0L
) {
    val utilizationPercent: Double
        get() = if (total > 0) (used / total) * 100.0 else 0.0
}

/**
 * Model card store - maps providers to their models
 */
class ModelCardStore {
    private val store = mutableMapOf<String, MutableList<ModelInfo>>()

    fun addModel(provider: String, model: ModelInfo) {
        store.getOrPut(provider) { mutableListOf() }.add(model)
    }

    fun getModels(provider: String): List<ModelInfo> = store[provider] ?: emptyList()

    fun getAllModels(): List<ModelInfo> = store.values.flatten()

    fun getProviders(): List<String> = store.keys.toList()

    fun removeProvider(provider: String) {
        store.remove(provider)
    }

    fun clear() {
        store.clear()
    }
}

/**
 * Rule engine for provider selection
 */
class RuleEngine {
    private val rules = mutableListOf<ProviderSelectionRule>()

    fun addRule(rule: ProviderSelectionRule) {
        rules.add(rule)
    }

    fun selectProvider(candidates: List<ProviderPotential>): String? {
        for (rule in rules) {
            when (rule) {
                is ProviderSelectionRule.Fixed -> {
                    if (candidates.any { it.provider == rule.provider && it.available }) {
                        return rule.provider
                    }
                }
                is ProviderSelectionRule.Fallback -> {
                    for (provider in rule.providers) {
                        if (candidates.any { it.provider == provider && it.available }) {
                            return provider
                        }
                    }
                }
                is ProviderSelectionRule.RoundRobin -> {
                    val available = candidates.filter { it.available && it.provider in rule.providers }
                    if (available.isNotEmpty()) {
                        return available[0].provider // Simplified
                    }
                }
                is ProviderSelectionRule.CostOptimized -> {
                    val available = candidates.filter { it.available && it.provider in rule.providers }
                    return available.minByOrNull { it.score }?.provider
                }
                is ProviderSelectionRule.QualityFirst -> {
                    val available = candidates.filter { it.available && it.provider in rule.providers }
                    return available.maxByOrNull { it.score }?.provider
                }
            }
        }
        return candidates.firstOrNull { it.available }?.provider
    }

    fun clearRules() {
        rules.clear()
    }
}

/**
 * Token tracking
 */
class TokenLedger {
    private val tokensByProvider = mutableMapOf<String, ULong>()
    private val tokensByModel = mutableMapOf<String, ULong>()
    private var totalTokens: ULong = 0uL

    fun record(provider: String, model: String, tokens: ULong) {
        tokensByProvider[provider] = (tokensByProvider[provider] ?: 0uL) + tokens
        tokensByModel[model] = (tokensByModel[model] ?: 0uL) + tokens
        totalTokens += tokens
    }

    fun totalByProvider(provider: String): ULong = tokensByProvider[provider] ?: 0uL
    fun totalByModel(model: String): ULong = tokensByModel[model] ?: 0uL
    fun totalTokens(): ULong = totalTokens
}
