package borg.literbike.ccek.keymux

/**
 * Type definitions for the Universal Model Facade
 *
 * This file contains types that are NOT defined in Dsel.kt, Cards.kt,
 * Decision.kt, TokenLedger.kt, Transform.kt, or Facade.kt.
 * Duplicated types (ProviderPotential, QuotaContainer, RuleEngine,
 * ModelCardStore, ProviderSelectionRule, TokenLedger) have been moved
 * to their proper source files ported from the Rust originals.
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
