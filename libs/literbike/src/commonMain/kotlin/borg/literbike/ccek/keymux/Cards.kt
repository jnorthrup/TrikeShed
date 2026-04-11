package borg.literbike.ccek.keymux

/**
 * Web Model Cards - Specialized metadata cache for agent reasoning
 */

/**
 * CachedModel - replaces modelmux dependency
 */
data class CachedModel(
    val id: String,
    val name: String,
    val supportsTools: Boolean,
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
    val contextWindow: Int? = null
)

/**
 * Model Card Store for web model cards
 */
class ModelCardStore {
    private val cards = mutableMapOf<String, WebModelCard>()

    fun upsert(id: String, card: WebModelCard) {
        cards[id] = card
    }

    /**
     * Bulk-populate cards from CachedModel data, inferring tags from model names.
     */
    fun populateFromCached(models: List<CachedModel>) {
        for (m in models) {
            val nameLower = m.name.lowercase()
            val idLower = m.id.lowercase()
            val haystack = "$nameLower $idLower"

            val tags = mutableListOf<String>()

            // Capability tags inferred from name
            if (haystack.contains("vision") || haystack.contains("4o") || haystack.contains("gemini")) {
                tags.add("vision")
            }
            if (haystack.contains("r1") || haystack.contains("thinking") || haystack.contains("reasoning") ||
                haystack.contains("o1") || haystack.contains("o3") || haystack.contains("o4")) {
                tags.add("reasoning")
            }
            if (haystack.contains("coder") || haystack.contains("code") || haystack.contains("codestral") ||
                haystack.contains("deepseek-coder") || haystack.contains("qwen2.5-coder")) {
                tags.add("coding")
            }
            if (haystack.contains("flash") || haystack.contains("mini") || haystack.contains("nano") ||
                haystack.contains("haiku") || haystack.contains("8b") || haystack.contains("instant")) {
                tags.add("fast")
            }
            if (m.supportsTools) {
                tags.add("tools")
            }
            if (haystack.contains("claude") || haystack.contains("gpt") || haystack.contains("gemini") ||
                haystack.contains("llama") || haystack.contains("sonnet") || haystack.contains("opus")) {
                tags.add("general")
            }

            // Infer reasoning_depth: 1-10 scale
            val reasoningDepth = when {
                tags.contains("reasoning") -> 8u
                haystack.contains("opus") || haystack.contains("405b") || haystack.contains("sonnet") -> 9u
                tags.contains("fast") -> 4u
                else -> 6u
            }

            // code_native: true for known coding-centric models or large general models
            val codeNative = tags.contains("coding") ||
                haystack.contains("claude") ||
                haystack.contains("gpt-4") ||
                haystack.contains("gemini")

            val inputCost = m.inputCostPerMillion ?: 0.0
            val outputCost = m.outputCostPerMillion ?: 0.0
            val pricing = if (inputCost > 0.0 || outputCost > 0.0) {
                Pricing(
                    prompt = inputCost,
                    completion = outputCost,
                    unit = "1M tokens"
                )
            } else null

            cards[m.id] = WebModelCard(
                tags = tags,
                contextWindow = (m.contextWindow ?: 0).toULong(),
                pricing = pricing,
                reasoningDepth = reasoningDepth,
                codeNative = codeNative
            )
        }
    }

    fun getAllModels(): List<String> = cards.keys.toList()

    fun getCard(modelId: String): WebModelCard? = cards[modelId]
}
