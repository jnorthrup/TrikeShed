package borg.trikeshed.keymux.dsel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

/**
 * DSL for provider selection rules.
 */
@Serializable
data class ProviderSelectionRule(
    val name: String,
    val conditions: List<SelectionCondition> = emptyList(),
    val priority: Byte = 0,
) {
    companion object {
        fun new(name: String, priority: Byte): ProviderSelectionRule {
            return ProviderSelectionRule(name = name, priority = priority)
        }
    }

    fun withMaxTokens(maxTokens: Int): ProviderSelectionRule {
        return copy(conditions = conditions + SelectionCondition.MaxTokens(maxTokens))
    }

    fun withCostThreshold(threshold: Double): ProviderSelectionRule {
        return copy(conditions = conditions + SelectionCondition.CostThreshold(threshold))
    }

    fun withFreeOnly(): ProviderSelectionRule {
        return copy(conditions = conditions + SelectionCondition.FreeOnly)
    }

    fun withProvider(provider: String): ProviderSelectionRule {
        return copy(conditions = conditions + SelectionCondition.ProviderName(provider))
    }

    fun matches(provider: ProviderPotential, tokens: Int): Boolean {
        for (condition in conditions) {
            when (condition) {
                is SelectionCondition.MaxTokens -> {
                    if (tokens > condition.maxTokens) return false
                }
                is SelectionCondition.CostThreshold -> {
                    if (provider.calculateCost(tokens) > condition.threshold) return false
                }
                is SelectionCondition.FreeOnly -> {
                    if (!provider.isFree) return false
                }
                is SelectionCondition.ProviderName -> {
                    if (provider.name != condition.name) return false
                }
            }
        }
        return true
    }
}

@Serializable
sealed class SelectionCondition {
    @Serializable
    data class MaxTokens(val maxTokens: Int) : SelectionCondition()
    @Serializable
    data class CostThreshold(val threshold: Double) : SelectionCondition()
    @Serializable
    object FreeOnly : SelectionCondition()
    @Serializable
    data class ProviderName(val name: String) : SelectionCondition()
}

/**
 * Metrics and logging for DSEL operations.
 */
class DSELMetrics {
    private val totalSelections = AtomicLong(0)
    private val selectionsByProvider = ConcurrentHashMap<String, Long>()
    private val quotaViolations = AtomicLong(0)
    private val hierarchicalTransforms = AtomicLong(0)
    private val totalTokensTracked = AtomicLong(0)
    private val rateLimitHits = AtomicLong(0)
    private val fallbackSelections = AtomicLong(0)

    fun recordSelection(provider: String, isFallback: Boolean = false) {
        totalSelections.incrementAndGet()
        selectionsByProvider.merge(provider, 1L) { a, b -> a + b }
        if (isFallback) {
            fallbackSelections.incrementAndGet()
        }
    }

    fun recordQuotaViolation() {
        quotaViolations.incrementAndGet()
    }

    fun recordHierarchicalTransform() {
        hierarchicalTransforms.incrementAndGet()
    }

    fun recordTokenUsage(tokens: Long) {
        totalTokensTracked.addAndGet(tokens)
    }

    fun recordRateLimitHit() {
        rateLimitHits.incrementAndGet()
    }

    fun getSelectionStats(): Triple<Long, Long, Double> {
        val total = totalSelections.get()
        val fallbacks = fallbackSelections.get()
        val fallbackRate = if (total > 0) (fallbacks.toDouble() / total.toDouble()) * 100.0 else 0.0
        return Triple(total, fallbacks, fallbackRate)
    }

    fun getTopProviders(limit: Int): List<Pair<String, Long>> {
        return selectionsByProvider.entries
            .map { it.key to it.value }
            .sortedByDescending { it.second }
            .take(limit)
    }
}

/**
 * Provider quota tracking for token ledger.
 */
@Serializable
data class ProviderQuotaTracking(
    val providerName: String,
    var tokensUsedToday: Long = 0L,
    var tokensUsedThisHour: Long = 0L,
    var estimatedRemainingQuota: Long = 0L,
    var quotaConfidence: Double = 0.8,
    var lastQuotaUpdate: Long = 0L,
)

/**
 * Rule-based provider selection engine with hierarchical support.
 */
class RuleEngine {
    private val rules = mutable.MutableList<ProviderSelectionRule>()
    private var hierarchicalSelector: HierarchicalModelSelector? = null
    var tokenLedgerEnabled = false
    val quotaTracking = ConcurrentHashMap<String, ProviderQuotaTracking>()
    val metrics = DSELMetrics()

    fun getMetrics(): DSELMetrics = metrics

    fun enableTokenLedger() {
        tokenLedgerEnabled = true

        // Initialize quota tracking for specific providers
        val providers = listOf(
            "kilo_code" to 1_000_000L,
            "opencode" to 500_000L,
            "openrouter" to 2_000_000L,
            "nvidia" to 3_000_000L,
            "moonshot" to 1_500_000L,
            "groq" to 2_000_000L,
            "xai" to 1_500_000L,
            "cerebras" to 2_000_000L,
        )

        for ((provider, quota) in providers) {
            quotaTracking[provider] = ProviderQuotaTracking(
                providerName = provider,
                tokensUsedToday = 0L,
                tokensUsedThisHour = 0L,
                estimatedRemainingQuota = quota,
                quotaConfidence = 0.8,
                lastQuotaUpdate = currentTimestamp(),
            )
        }
    }

    fun addRule(rule: ProviderSelectionRule) {
        rules.add(rule)
    }

    fun setHierarchicalSelector(selector: HierarchicalModelSelector) {
        hierarchicalSelector = selector
    }

    fun trackTokenUsage(provider: String, tokens: Long): Result<Unit> {
        if (!tokenLedgerEnabled) return Result.success(Unit)

        val tracking = quotaTracking.getOrPut(provider) {
            ProviderQuotaTracking(
                providerName = provider,
                tokensUsedToday = 0L,
                tokensUsedThisHour = 0L,
                estimatedRemainingQuota = when (provider) {
                    "kilo_code" -> 1_000_000L
                    "opencode" -> 500_000L
                    "openrouter" -> 2_000_000L
                    "nvidia" -> 3_000_000L
                    "moonshot" -> 1_500_000L
                    "groq" -> 2_000_000L
                    "xai" -> 1_500_000L
                    "cerebras" -> 2_000_000L
                    else -> 100_000L
                },
                quotaConfidence = 0.7,
                lastQuotaUpdate = currentTimestamp(),
            )
        }

        tracking.tokensUsedToday += tokens
        tracking.tokensUsedThisHour += tokens

        if (tracking.estimatedRemainingQuota >= tokens) {
            tracking.estimatedRemainingQuota -= tokens
            tracking.quotaConfidence *= 0.99
        } else {
            tracking.estimatedRemainingQuota = 0
            tracking.quotaConfidence = 0.0
        }

        tracking.lastQuotaUpdate = currentTimestamp()
        metrics.recordTokenUsage(tokens)

        return Result.success(Unit)
    }

    fun hasSufficientQuota(provider: String, tokensNeeded: Long): Boolean {
        if (!tokenLedgerEnabled) return true
        return quotaTracking[provider]?.let { it.estimatedRemainingQuota >= tokensNeeded } ?: false
    }

    fun getQuotaStatus(provider: String): Triple<Long, Long, Double>? {
        return quotaTracking[provider]?.let { (it.tokensUsedToday, it.estimatedRemainingQuota, it.quotaConfidence) }
    }

    fun resetHourlyUsage() {
        for (tracking in quotaTracking.values) {
            tracking.tokensUsedThisHour = 0
        }
    }

    fun resetDailyUsage() {
        for (tracking in quotaTracking.values) {
            tracking.tokensUsedToday = 0
            tracking.estimatedRemainingQuota = when (tracking.providerName) {
                "kilo_code" -> 1_000_000L
                "opencode" -> 500_000L
                "openrouter" -> 2_000_000L
                "nvidia" -> 3_000_000L
                "moonshot" -> 1_500_000L
                "groq" -> 2_000_000L
                "xai" -> 1_500_000L
                "cerebras" -> 2_000_000L
                else -> tracking.estimatedRemainingQuota
            }
            tracking.quotaConfidence = 0.8
        }
    }

    fun getAllQuotaTracking(): Map<String, ProviderQuotaTracking> {
        return quotaTracking.toMap()
    }

    fun selectProvider(
        providers: List<ProviderPotential>,
        tokens: Int,
        modelId: String? = null,
    ): ProviderPotential? {
        // If modelId is provided and hierarchical selector exists, try transformations
        if (modelId != null && hierarchicalSelector != null) {
            metrics.recordHierarchicalTransform()
            val transformations = hierarchicalSelector!!.handleComplexTransformations(modelId)

            for (transformed in transformations) {
                val parts = transformed.split('/').filter { it.isNotBlank() }
                if (parts.isNotEmpty()) {
                    val providerName = parts[0]
                    for (provider in providers) {
                        if (provider.name == providerName && provider.canHandle(tokens)) {
                            if (tokenLedgerEnabled && !hasSufficientQuota(provider.name, tokens.toLong())) {
                                continue
                            }
                            if (rules.isEmpty() || rules.any { it.matches(provider, tokens) }) {
                                return provider
                            }
                        }
                    }
                }
            }
        }

        // Fallback to standard rule-based selection with quota consideration
        return selectProviderByRulesWithQuota(providers, tokens)
    }

    private fun selectProviderByRulesWithQuota(
        providers: List<ProviderPotential>,
        tokens: Int,
    ): ProviderPotential? {
        var matches = providers.filter { it.canHandle(tokens) }

        if (tokenLedgerEnabled) {
            matches = matches.filter { hasSufficientQuota(it.name, tokens.toLong()) }
        }

        for (rule in rules) {
            for (provider in matches) {
                if (rule.matches(provider, tokens)) {
                    return provider
                }
            }
        }

        return matches.minByOrNull { it.getPriorityScore() }
    }

    fun selectProviderEnhanced(
        providers: List<ProviderPotential>,
        hierarchicalModelId: String,
        tokens: Int,
    ): ProviderPotential? {
        // First, try direct provider match
        val parts = hierarchicalModelId.split('/').filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            val providerName = parts[0]
            for (provider in providers) {
                if (provider.name == providerName && provider.canHandle(tokens)) {
                    if (rules.isEmpty() || rules.any { it.matches(provider, tokens) }) {
                        return provider
                    }
                }
            }
        }

        // If hierarchical selector exists, try transformations
        hierarchicalSelector?.let { selector ->
            val bestApproximation = selector.selectBestApproximation(hierarchicalModelId)
            if (bestApproximation != null && bestApproximation.canHandle(tokens)) {
                return bestApproximation
            }
        }

        // Fallback to standard selection
        return selectProviderByRulesWithQuota(providers, tokens)
    }

    private fun currentTimestamp(): Long {
        return Instant.now().epochSeconds
    }
}

/**
 * Hierarchical model ID processor for DSEL.
 */
class HierarchicalModelProcessor {
    private val transformations = mutable.MutableList<Pair<String, String>>()
    private val providerMappings = mutable.MutableMap<String, MutableList<String>>()

    fun addTransformation(pattern: String, replacement: String) {
        transformations.add(pattern to replacement)
    }

    fun addProviderMapping(provider: String, aliases: List<String>) {
        providerMappings[provider] = aliases.toMutableList()
    }

    fun processModelId(modelId: String): Pair<String, String> {
        var processed = modelId

        for ((pattern, replacement) in transformations) {
            processed = processed.replace(pattern, replacement)
        }

        val parts = processed.split('/').filter { it.isNotBlank() }

        if (parts.size >= 2) {
            var provider = parts[0]
            val model = parts.drop(1).joinToString("/")

            for ((canonical, aliases) in providerMappings) {
                if (aliases.contains(provider) || provider == canonical) {
                    return canonical to model
                }
            }

            return provider to model
        } else if (parts.size == 1) {
            return "unknown" to parts[0]
        } else {
            return "unknown" to processed
        }
    }

    fun findBestProviderApproximation(
        hierarchicalModelId: String,
        availableProviders: List<String>,
    ): String? {
        val (provider, _) = processModelId(hierarchicalModelId)

        if (availableProviders.contains(provider)) {
            return provider
        }

        for ((canonical, aliases) in providerMappings) {
            if (availableProviders.contains(canonical)) {
                for (alias in aliases) {
                    if (hierarchicalModelId.contains(alias)) {
                        return canonical
                    }
                }
            }
        }

        return null
    }
}
