package borg.literbike.ccek.keymux

import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

// ============================================================================
// Provider quota and priority configurations (port of dsel.rs)
// ============================================================================

/**
 * Represents a provider with quota and priority information
 */
data class ProviderPotential(
    val name: String,
    val availableTokens: Int,
    val priority: UByte,
    val costPerMillion: Double,
    val isFree: Boolean,
    val freeQuota: FreeQuotaConfig? = null,
    val quotaTimeframe: QuotaTimeframe? = null,
    val rateLimit: RateLimitConfig? = null
) {
    companion object {
        fun new(
            name: String,
            availableTokens: Int,
            priority: UByte,
            costPerMillion: Double,
            isFree: Boolean
        ): ProviderPotential {
            return ProviderPotential(
                name = name,
                availableTokens = availableTokens,
                priority = priority,
                costPerMillion = costPerMillion,
                isFree = isFree
            )
        }
    }

    fun withFreeQuota(dailyTokens: Int, monthlyTokens: Int, resetHour: UByte): ProviderPotential {
        return copy(
            freeQuota = FreeQuotaConfig(
                dailyTokens = dailyTokens,
                monthlyTokens = monthlyTokens,
                resetHour = resetHour
            )
        )
    }

    fun withTimeframeQuota(
        timeframe: TimeframeType,
        limit: Int,
        resetTimestamp: Long
    ): ProviderPotential {
        return copy(
            quotaTimeframe = QuotaTimeframe(
                timeframeType = timeframe,
                quotaLimit = limit,
                currentUsage = 0,
                resetTimestamp = resetTimestamp
            )
        )
    }

    fun withRateLimit(
        perMinute: ULong,
        perHour: ULong,
        perDay: ULong,
        burst: ULong
    ): ProviderPotential {
        return copy(
            rateLimit = RateLimitConfig(
                requestsPerMinute = perMinute,
                requestsPerHour = perHour,
                requestsPerDay = perDay,
                burstLimit = burst
            )
        )
    }

    /** Calculate cost for given token count */
    fun calculateCost(tokens: Int): Double {
        return if (isFree) 0.0 else (tokens.toDouble() * costPerMillion) / 1_000_000.0
    }

    /** Check if provider can handle the request considering free quotas */
    fun canHandle(tokens: Int): Boolean {
        if (tokens > availableTokens) return false

        freeQuota?.let { fq ->
            return tokens <= fq.dailyTokens
        }

        quotaTimeframe?.let { tf ->
            return (tf.currentUsage + tokens) <= tf.quotaLimit
        }

        return true
    }

    /** Get priority score (lower is better) */
    fun getPriorityScore(): UByte {
        return if (isFree) priority - 1u else priority
    }

    /** Check if rate limited */
    fun isRateLimited(currentRequests: ULong, timeframe: String): Boolean {
        rateLimit?.let { rl ->
            return when (timeframe) {
                "minute" -> currentRequests > rl.requestsPerMinute
                "hour" -> currentRequests > rl.requestsPerHour
                "day" -> currentRequests > rl.requestsPerDay
                else -> false
            }
        }
        return false
    }
}

@kotlinx.serialization.Serializable
data class FreeQuotaConfig(
    val dailyTokens: Int,
    val monthlyTokens: Int,
    val resetHour: UByte,
    val resetDayOfMonth: UByte? = null
)

@kotlinx.serialization.Serializable
data class QuotaTimeframe(
    val timeframeType: TimeframeType,
    val quotaLimit: Int,
    val currentUsage: Int,
    val resetTimestamp: Long
)

@kotlinx.serialization.Serializable
enum class TimeframeType {
    Hourly, Daily, Weekly, Monthly, Yearly
}

@kotlinx.serialization.Serializable
data class RateLimitConfig(
    val requestsPerMinute: ULong,
    val requestsPerHour: ULong,
    val requestsPerDay: ULong,
    val burstLimit: ULong
)

// ============================================================================
// Provider-specific quota tracking for API token ledger
// ============================================================================

@kotlinx.serialization.Serializable
data class ProviderTokenLedger(
    val providerName: String,
    val totalTokensUsed: ULong,
    val tokensUsedToday: ULong,
    val tokensUsedThisHour: ULong,
    val lastApiCheck: Long? = null,
    val apiStatus: ProviderApiStatus,
    val vagueQuotaRemaining: VagueQuota? = null
)

@kotlinx.serialization.Serializable
enum class ProviderApiStatus {
    Healthy, Degraded, RateLimited, AuthenticationFailed, Unknown
}

@kotlinx.serialization.Serializable
data class VagueQuota(
    val estimatedRemaining: ULong,
    val confidence: Double,
    val lastUpdated: Long,
    val source: QuotaSource
)

@kotlinx.serialization.Serializable
enum class QuotaSource {
    ApiDirect, EstimatedFromUsage, ManualConfiguration, Unknown
}

// ============================================================================
// Provider configuration structs
// ============================================================================

@kotlinx.serialization.Serializable
data class KiloCodeConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

@kotlinx.serialization.Serializable
data class OpenCodeConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

@kotlinx.serialization.Serializable
data class OpenRouterConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

@kotlinx.serialization.Serializable
data class NvidiaConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

@kotlinx.serialization.Serializable
data class MoonshotConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

@kotlinx.serialization.Serializable
data class GroqConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

@kotlinx.serialization.Serializable
data class XAIConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

@kotlinx.serialization.Serializable
data class CerebrasConfig(
    val apiKey: String? = null,
    val baseUrl: String,
    val estimatedDailyLimit: ULong,
    val apiCheckInterval: ULong,
    val lastApiCheck: Long,
    val currentLedger: ProviderTokenLedger
)

// ============================================================================
// Quota Container
// ============================================================================

@kotlinx.serialization.Serializable
data class QuotaContainer(
    val name: String,
    val providers: MutableMap<String, ProviderPotential> = mutableMapOf(),
    var totalQuota: Int = 0,
    var usedQuota: Int = 0
) {
    companion object {
        fun new(name: String): QuotaContainer {
            return QuotaContainer(name = name)
        }
    }

    fun addProvider(
        name: String,
        tokens: Int,
        priority: UByte,
        costPerMillion: Double,
        isFree: Boolean
    ) {
        val provider = ProviderPotential.new(name, tokens, priority, costPerMillion, isFree)
        providers[name] = provider
        totalQuota += tokens
    }

    fun addProviderFromStruct(provider: ProviderPotential) {
        providers[provider.name] = provider
        totalQuota += provider.availableTokens
    }

    fun addFreeProvider(
        name: String,
        tokens: Int,
        priority: UByte,
        dailyTokens: Int,
        monthlyTokens: Int,
        resetHour: UByte
    ) {
        val provider = ProviderPotential.new(name, tokens, priority, 0.0, true)
            .withFreeQuota(dailyTokens, monthlyTokens, resetHour)
        addProviderFromStruct(provider)
    }

    fun addTimeframeProvider(
        name: String,
        tokens: Int,
        priority: UByte,
        costPerMillion: Double,
        timeframe: TimeframeType,
        quotaLimit: Int,
        resetTimestamp: Long
    ) {
        val provider = ProviderPotential.new(name, tokens, priority, costPerMillion, false)
            .withTimeframeQuota(timeframe, quotaLimit, resetTimestamp)
        addProviderFromStruct(provider)
    }

    fun canAllocate(tokens: Int): Boolean {
        val available = totalQuota - usedQuota
        return tokens <= available
    }

    fun allocate(tokens: Int): ProviderPotential? {
        if (!canAllocate(tokens)) return null

        val bestProvider = providers.values
            .filter { it.canHandle(tokens) }
            .minByOrNull { it.getPriorityScore() }

        return bestProvider?.also {
            usedQuota += tokens
        }
    }

    fun selectProvider(tokens: Int): ProviderPotential? {
        return providers.values
            .filter { it.canHandle(tokens) }
            .minByOrNull { it.getPriorityScore() }
    }

    fun getProvider(name: String): ProviderPotential? = providers[name]

    fun getProvidersByPriority(): List<ProviderPotential> {
        return providers.values.sortedBy { it.getPriorityScore() }
    }
}

// ============================================================================
// Prefix Transformation
// ============================================================================

data class PrefixTransformation(
    val pattern: String,
    val replacement: String,
    val priority: UByte
)

/**
 * Hierarchical model selector that handles prefix transformations
 */
class HierarchicalModelSelector(
    private val baseSelector: QuotaContainer
) {
    private val prefixCache = mutableMapOf<String, String>()
    private val transformationRules = mutableListOf<PrefixTransformation>()

    fun addTransformationRule(pattern: String, replacement: String, priority: UByte) {
        transformationRules.add(PrefixTransformation(pattern, replacement, priority))
        transformationRules.sortByDescending { it.priority }
    }

    fun transformModelId(modelId: String): String {
        prefixCache[modelId]?.let { return it }

        var bestMatch = modelId
        var bestScore = 0u
        var bestRule: PrefixTransformation? = null

        for (rule in transformationRules) {
            val matched = applyTransformationRule(modelId, rule)
            if (matched != null) {
                val score = rule.priority.toUInt() * 100u +
                    calculateMatchQuality(matched.length, modelId.length).toUInt()
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = matched
                    bestRule = rule
                }
            }
        }

        bestRule?.let {
            // Log transformation: println("Transformed $modelId -> $bestMatch using rule: ${it.pattern}")
        }

        prefixCache[modelId] = bestMatch
        return bestMatch
    }

    private fun applyTransformationRule(modelId: String, rule: PrefixTransformation): String? {
        val patterns = listOf(
            rule.pattern + if (rule.pattern.endsWith("/")) "" else "/",
            "^${rule.pattern}(?:${rule.pattern}(?:${rule.pattern}/)+)",
            "^${rule.pattern}(.*)"
        )

        for (pattern in patterns) {
            val replaced = replacePattern(modelId, pattern, rule.replacement)
            if (replaced != null) return replaced
        }
        return null
    }

    private fun replacePattern(modelId: String, pattern: String, replacement: String): String? {
        return try {
            val regex = Regex(pattern)
            if (regex.containsMatchIn(modelId)) {
                regex.replace(modelId, replacement)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateMatchQuality(transformedLen: Int, originalLen: Int): Int {
        return if (transformedLen < originalLen) {
            (originalLen - transformedLen) * 10
        } else 0
    }

    fun handleComplexTransformations(modelId: String): List<String> {
        val transformations = mutableListOf<String>()

        val patterns = listOf(
            "^/litellm/litellm/litellm/(.+)$" to "/litellm/$1",
            "^/ccswitch/ccswitch/ccswitch/(.+)$" to "/ccswitch/$1",
            "^/openai/openai/openai/(.+)$" to "/openai/$1",
            "^/anthropic/anthropic/anthropic/(.+)$" to "/anthropic/$1",
            "^/(.+)/\\1/\\1/(.+)$" to "/$1/$2",
            "^/(.+)/\\1/(.+)$" to "/$1/$2"
        )

        for ((pattern, replacement) in patterns) {
            try {
                val regex = Regex(pattern)
                if (regex.containsMatchIn(modelId)) {
                    val transformed = regex.replace(modelId, replacement)
                    if (transformed !in transformations) {
                        transformations.add(transformed)
                    }
                }
            } catch (_: Exception) { /* skip invalid regex */ }
        }

        if (transformations.isEmpty()) {
            val parts = modelId.split('/').filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val provider = parts[parts.size - 2]
                val model = parts[parts.size - 1]
                transformations.add("/$provider/$model")
            }
        }

        return transformations
    }

    fun selectBestApproximation(hierarchicalModelId: String): ProviderPotential? {
        val parts = hierarchicalModelId.split('/').filter { it.isNotEmpty() }
        val providerName = parts.firstOrNull() ?: return null
        return baseSelector.getProvider(providerName)
    }
}

// ============================================================================
// Provider Selection Rule (DSL version)
// ============================================================================

class ProviderSelectionRule(
    val name: String,
    val conditions: MutableList<SelectionCondition> = mutableListOf(),
    val priority: UByte
) {
    sealed class SelectionCondition {
        data class MaxTokens(val max: Int) : SelectionCondition()
        data class CostThreshold(val threshold: Double) : SelectionCondition()
        data object FreeOnly : SelectionCondition()
        data class ProviderName(val name: String) : SelectionCondition()
    }

    fun withMaxTokens(maxTokens: Int): ProviderSelectionRule {
        conditions.add(SelectionCondition.MaxTokens(maxTokens))
        return this
    }

    fun withCostThreshold(threshold: Double): ProviderSelectionRule {
        conditions.add(SelectionCondition.CostThreshold(threshold))
        return this
    }

    fun withFreeOnly(): ProviderSelectionRule {
        conditions.add(SelectionCondition.FreeOnly)
        return this
    }

    fun withProvider(provider: String): ProviderSelectionRule {
        conditions.add(SelectionCondition.ProviderName(provider))
        return this
    }

    fun matches(provider: ProviderPotential, tokens: Int): Boolean {
        for (condition in conditions) {
            when (condition) {
                is SelectionCondition.MaxTokens -> {
                    if (tokens > condition.max) return false
                }
                is SelectionCondition.CostThreshold -> {
                    if (provider.calculateCost(tokens) > condition.threshold) return false
                }
                SelectionCondition.FreeOnly -> {
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

// ============================================================================
// DSEL Metrics
// ============================================================================

class DSELMetrics {
    @Volatile private var totalSelections: ULong = 0uL
    private val selectionsByProvider = mutableMapOf<String, ULong>()
    @Volatile private var quotaViolations: ULong = 0uL
    @Volatile private var hierarchicalTransforms: ULong = 0uL
    @Volatile private var totalTokensTracked: ULong = 0uL
    @Volatile private var rateLimitHits: ULong = 0uL
    @Volatile private var fallbackSelections: ULong = 0uL

    fun recordSelection(provider: String, isFallback: Boolean) {
        totalSelections++
        selectionsByProvider[provider] = (selectionsByProvider[provider] ?: 0uL) + 1uL
        if (isFallback) fallbackSelections++
    }

    fun recordQuotaViolation() { quotaViolations++ }
    fun recordHierarchicalTransform() { hierarchicalTransforms++ }
    fun recordTokenUsage(tokens: ULong) { totalTokensTracked += tokens }
    fun recordRateLimit() { rateLimitHits++ }

    fun getSelectionStats(): Triple<ULong, ULong, Double> {
        val fallbackRate = if (totalSelections > 0uL) (fallbackSelections.toDouble() / totalSelections.toDouble()) * 100.0 else 0.0
        return Triple(totalSelections, fallbackSelections, fallbackRate)
    }

    fun getTopProviders(limit: Int): List<Pair<String, ULong>> {
        return selectionsByProvider.toList().sortedByDescending { it.second }.take(limit)
    }

    fun export(): Map<String, Any> {
        val (total, fallbacks, fallbackRate) = getSelectionStats()
        return mapOf(
            "total_selections" to totalSelections,
            "selections_by_provider" to selectionsByProvider.toMap(),
            "quota_violations" to quotaViolations,
            "hierarchical_transforms" to hierarchicalTransforms,
            "total_tokens_tracked" to totalTokensTracked,
            "rate_limit_hits" to rateLimitHits,
            "fallback_selections" to fallbacks,
            "fallback_rate_percent" to fallbackRate,
            "top_providers" to getTopProviders(5)
        )
    }
}

// ============================================================================
// Provider Quota Tracking
// ============================================================================

data class ProviderQuotaTracking(
    val providerName: String,
    var tokensUsedToday: ULong = 0uL,
    var tokensUsedThisHour: ULong = 0uL,
    var estimatedRemainingQuota: ULong,
    var quotaConfidence: Double = 0.8,
    var lastQuotaUpdate: Long
)

// ============================================================================
// Rule Engine
// ============================================================================

class RuleEngine {
    private val rules = mutableListOf<ProviderSelectionRule>()
    private var hierarchicalSelector: HierarchicalModelSelector? = null
    private var tokenLedgerEnabled: Boolean = false
    val quotaTracking = mutableMapOf<String, ProviderQuotaTracking>()
    val metrics: DSELMetrics = DSELMetrics()

    companion object {
        private fun currentTimestamp(): Long = Clocks.System.now() / 1000

        private fun initialQuotaForProvider(provider: String): ULong {
            return when (provider) {
                "kilo_code" -> 1_000_000uL
                "opencode" -> 500_000uL
                "openrouter" -> 2_000_000uL
                "nvidia" -> 3_000_000uL
                "moonshot" -> 1_500_000uL
                "groq" -> 2_000_000uL
                "xai" -> 1_500_000uL
                "cerebras" -> 2_000_000uL
                else -> 100_000uL
            }
        }
    }

    fun enableTokenLedger() {
        tokenLedgerEnabled = true
        val providers = listOf("kilo_code", "opencode", "openrouter", "nvidia", "moonshot", "groq", "xai", "cerebras")
        for (provider in providers) {
            quotaTracking[provider] = ProviderQuotaTracking(
                providerName = provider,
                estimatedRemainingQuota = initialQuotaForProvider(provider),
                quotaConfidence = 0.8,
                lastQuotaUpdate = currentTimestamp()
            )
        }
    }

    fun addRule(rule: ProviderSelectionRule) { rules.add(rule) }

    fun setHierarchicalSelector(selector: HierarchicalModelSelector) {
        hierarchicalSelector = selector
    }

    fun trackTokenUsage(provider: String, tokens: ULong) {
        if (!tokenLedgerEnabled) return

        val tracking = quotaTracking.getOrPut(provider) {
            ProviderQuotaTracking(
                providerName = provider,
                estimatedRemainingQuota = initialQuotaForProvider(provider),
                quotaConfidence = 0.7,
                lastQuotaUpdate = currentTimestamp()
            )
        }

        tracking.tokensUsedToday += tokens
        tracking.tokensUsedThisHour += tokens

        if (tracking.estimatedRemainingQuota >= tokens) {
            tracking.estimatedRemainingQuota -= tokens
            tracking.quotaConfidence *= 0.99
        } else {
            tracking.estimatedRemainingQuota = 0uL
            tracking.quotaConfidence = 0.0
        }
        tracking.lastQuotaUpdate = currentTimestamp()
        metrics.recordTokenUsage(tokens)
    }

    fun hasSufficientQuota(provider: String, tokensNeeded: ULong): Boolean {
        if (!tokenLedgerEnabled) return true
        val tracking = quotaTracking[provider] ?: return false
        return tracking.estimatedRemainingQuota >= tokensNeeded
    }

    fun getQuotaStatus(provider: String): Triple<ULong, ULong, Double>? {
        val tracking = quotaTracking[provider] ?: return null
        return Triple(tracking.tokensUsedToday, tracking.estimatedRemainingQuota, tracking.quotaConfidence)
    }

    fun resetHourlyUsage() {
        for (tracking in quotaTracking.values) {
            tracking.tokensUsedThisHour = 0uL
        }
    }

    fun resetDailyUsage() {
        for (tracking in quotaTracking.values) {
            tracking.tokensUsedToday = 0uL
            tracking.estimatedRemainingQuota = initialQuotaForProvider(tracking.providerName)
            tracking.quotaConfidence = 0.8
        }
    }

    fun getAllQuotaTracking(): Map<String, ProviderQuotaTracking> = quotaTracking.toMap()

    /**
     * Select provider based on rules with hierarchical model ID support and quota tracking
     */
    fun selectProvider(
        providers: List<ProviderPotential>,
        tokens: Int,
        modelId: String? = null
    ): ProviderPotential? {
        if (modelId != null && hierarchicalSelector != null) {
            metrics.recordHierarchicalTransform()
            val transformations = hierarchicalSelector!!.handleComplexTransformations(modelId)

            for (transformed in transformations) {
                val parts = transformed.split('/').filter { it.isNotEmpty() }
                val providerName = parts.firstOrNull() ?: continue

                for (provider in providers) {
                    if (provider.name == providerName && provider.canHandle(tokens)) {
                        if (tokenLedgerEnabled && !hasSufficientQuota(provider.name, tokens.toULong())) {
                            continue
                        }
                        if (rules.isEmpty() || rules.any { it.matches(provider, tokens) }) {
                            return provider
                        }
                    }
                }
            }
        }

        val result = selectProviderByRulesWithQuota(providers, tokens)
        if (result == null) metrics.recordQuotaViolation()
        return result
    }

    private fun selectProviderByRules(providers: List<ProviderPotential>, tokens: Int): ProviderPotential? {
        val matches = providers.filter { it.canHandle(tokens) }

        for (rule in rules) {
            for (provider in matches) {
                if (rule.matches(provider, tokens)) return provider
            }
        }

        return matches.minByOrNull { it.getPriorityScore() }
    }

    private fun selectProviderByRulesWithQuota(
        providers: List<ProviderPotential>,
        tokens: Int
    ): ProviderPotential? {
        var matches = providers.filter { it.canHandle(tokens) }

        if (tokenLedgerEnabled) {
            matches = matches.filter { hasSufficientQuota(it.name, tokens.toULong()) }
        }

        for (rule in rules) {
            for (provider in matches) {
                if (rule.matches(provider, tokens)) return provider
            }
        }

        return matches.minByOrNull { it.getPriorityScore() }
    }

    fun selectProviderEnhanced(
        providers: List<ProviderPotential>,
        hierarchicalModelId: String,
        tokens: Int
    ): ProviderPotential? {
        val parts = hierarchicalModelId.split('/').filter { it.isNotEmpty() }
        val providerName = parts.firstOrNull()
        if (providerName != null) {
            for (provider in providers) {
                if (provider.name == providerName && provider.canHandle(tokens)) {
                    if (rules.isEmpty() || rules.any { it.matches(provider, tokens) }) {
                        return provider
                    }
                }
            }
        }

        hierarchicalSelector?.let { selector ->
            val bestApproximation = selector.selectBestApproximation(hierarchicalModelId)
            if (bestApproximation?.canHandle(tokens) == true) {
                return bestApproximation
            }
        }

        return selectProviderByRules(providers, tokens)
    }
}

// ============================================================================
// DSEL Builder
// ============================================================================

class DSELBuilder {
    private var container = QuotaContainer.new("default")
    private val prefixTransformations = mutableMapOf<String, MutableList<String>>()

    fun withQuota(name: String, totalQuota: Int): DSELBuilder {
        container = container.copy(name = name, totalQuota = totalQuota)
        return this
    }

    fun withProvider(
        name: String,
        tokens: Int,
        priority: UByte,
        costPerMillion: Double,
        isFree: Boolean
    ): DSELBuilder {
        container.addProvider(name, tokens, priority, costPerMillion, isFree)
        return this
    }

    fun withFreeProvider(
        name: String,
        tokens: Int,
        priority: UByte,
        dailyTokens: Int,
        monthlyTokens: Int,
        resetHour: UByte
    ): DSELBuilder {
        container.addFreeProvider(name, tokens, priority, dailyTokens, monthlyTokens, resetHour)
        return this
    }

    fun withTimeframeProvider(
        name: String,
        tokens: Int,
        priority: UByte,
        costPerMillion: Double,
        timeframe: TimeframeType,
        quotaLimit: Int,
        resetTimestamp: Long
    ): DSELBuilder {
        container.addTimeframeProvider(name, tokens, priority, costPerMillion, timeframe, quotaLimit, resetTimestamp)
        return this
    }

    fun withRateLimitedProvider(
        name: String,
        tokens: Int,
        priority: UByte,
        costPerMillion: Double,
        perMinute: ULong,
        perHour: ULong,
        perDay: ULong,
        burst: ULong
    ): DSELBuilder {
        val provider = ProviderPotential.new(name, tokens, priority, costPerMillion, false)
            .withRateLimit(perMinute, perHour, perDay, burst)
        container.addProviderFromStruct(provider)
        return this
    }

    fun withPrefixTransformation(fromPrefix: String, toPrefix: String): DSELBuilder {
        prefixTransformations.getOrPut(fromPrefix) { mutableListOf() }.add(toPrefix)
        return this
    }

    fun build(): Result<QuotaContainer> {
        if (container.providers.isEmpty()) return Result.failure(Exception("No providers defined"))
        if (container.totalQuota == 0) return Result.failure(Exception("Total quota must be greater than zero"))
        return Result.success(container)
    }

    fun buildWithRuleEngine(): Result<RuleEngine> {
        if (container.providers.isEmpty()) return Result.failure(Exception("No providers defined"))

        val hierarchicalSelector = HierarchicalModelSelector(container.copy())

        for ((fromPrefix, toPrefixes) in prefixTransformations) {
            for (toPrefix in toPrefixes) {
                hierarchicalSelector.addTransformationRule(fromPrefix, toPrefix, 100u)
            }
        }

        hierarchicalSelector.addTransformationRule("/litellm/litellm/litellm/", "/litellm/", 100u)
        hierarchicalSelector.addTransformationRule("/ccswitch/ccswitch/ccswitch/", "/ccswitch/", 90u)
        hierarchicalSelector.addTransformationRule("/openai/openai/openai/", "/openai/", 80u)
        hierarchicalSelector.addTransformationRule("/anthropic/anthropic/anthropic/", "/anthropic/", 85u)

        val ruleEngine = RuleEngine()
        ruleEngine.setHierarchicalSelector(hierarchicalSelector)
        ruleEngine.enableTokenLedger()

        for ((name, provider) in container.providers) {
            val tracking = ProviderQuotaTracking(
                providerName = name,
                estimatedRemainingQuota = provider.availableTokens.toULong(),
                quotaConfidence = 0.9,
                lastQuotaUpdate = Clocks.System.now() / 1000
            )
            ruleEngine.quotaTracking[name] = tracking
        }

        return Result.success(ruleEngine)
    }

    fun buildWithHierarchicalSelector(): Result<Pair<QuotaContainer, HierarchicalModelSelector>> {
        if (container.providers.isEmpty()) return Result.failure(Exception("No providers defined"))

        val hierarchicalSelector = HierarchicalModelSelector(container.copy())

        for ((fromPrefix, toPrefixes) in prefixTransformations) {
            for (toPrefix in toPrefixes) {
                hierarchicalSelector.addTransformationRule(fromPrefix, toPrefix, 100u)
            }
        }

        hierarchicalSelector.addTransformationRule("/litellm/litellm/litellm/", "/litellm/", 100u)
        hierarchicalSelector.addTransformationRule("/ccswitch/ccswitch/ccswitch/", "/ccswitch/", 90u)
        hierarchicalSelector.addTransformationRule("/openai/openai/openai/", "/openai/", 80u)
        hierarchicalSelector.addTransformationRule("/anthropic/anthropic/anthropic/", "/anthropic/", 85u)

        return Result.success(container to hierarchicalSelector)
    }
}

// ============================================================================
// Routing functions (port of route, track_tokens, discover_providers, etc.)
// ============================================================================

/**
 * Route a model ID to (provider_name, base_url, key_env_var).
 */
fun route(model: String): Triple<String, String, String>? {
    val provider = model.split('/').firstOrNull() ?: model

    return when (provider) {
        "anthropic" -> Triple("anthropic", "https://api.anthropic.com/v1", "ANTHROPIC_API_KEY")
        "openai" -> Triple("openai", "https://api.openai.com/v1", "OPENAI_API_KEY")
        "google", "gemini" -> Triple("google", "https://generativelanguage.googleapis.com/v1beta/openai", "GOOGLE_API_KEY")
        "groq" -> Triple("groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY")
        "openrouter" -> Triple("openrouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY")
        "mistral" -> Triple("mistral", "https://api.mistral.ai/v1", "MISTRAL_API_KEY")
        "xai", "grok" -> Triple("xai", "https://api.x.ai/v1", "XAI_API_KEY")
        "cerebras" -> Triple("cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY")
        "ollama" -> Triple("ollama", "http://localhost:11434/v1", "")
        "lmstudio" -> Triple("lmstudio", "http://localhost:1234/v1", "")
        "moonshot", "kimi" -> Triple("moonshot", "https://api.moonshot.cn/v1", "MOONSHOT_API_KEY")
        "perplexity" -> Triple("perplexity", "https://api.perplexity.ai", "PERPLEXITY_API_KEY")
        "deepseek" -> Triple("deepseek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY")
        "huggingface" -> Triple("huggingface", "https://api-inference.huggingface.co/v1", "HF_API_KEY")
        "kilo" -> Triple("kilo", "https://api.kilocode.ai", "KILO_CODE_API_KEY")
        "zai" -> Triple("zai", "https://api.01.ai/v1", "ZAI_API_KEY")
        "arcee" -> Triple("arcee", "https://api.arcee.ai/v1", "ARCEE_API_KEY")
        "opencode-go" -> {
            val modelName = model.split('/').getOrElse(1) { "" }
            val endpoint = when (modelName) {
                "glm-5", "kimi-k2.5" -> "https://opencode.ai/zen/go/v1/chat/completions"
                "minimax-m2.7", "minimax-m2.5" -> "https://opencode.ai/zen/go/v1/messages"
                else -> "https://opencode.ai/zen/go/v1"
            }
            Triple("opencode-go", endpoint, "OPENCODE_API_KEY")
        }
        else -> null
    }
}

/**
 * Track token usage for quota accounting.
 */
fun trackTokens(provider: String, tokens: ULong): Result<Unit> {
    // log: println("token usage: provider=$provider tokens=$tokens")
    return Result.success(Unit)
}

/**
 * Return all providers that have an API key set in the environment.
 */
fun discoverProviders(): List<ProviderDef> {
    val candidates = listOf(
        Triple("anthropic", "https://api.anthropic.com/v1", "ANTHROPIC_API_KEY"),
        Triple("openai", "https://api.openai.com/v1", "OPENAI_API_KEY"),
        Triple("google", "https://generativelanguage.googleapis.com/v1beta/openai", "GOOGLE_API_KEY"),
        Triple("groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY"),
        Triple("openrouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY"),
        Triple("mistral", "https://api.mistral.ai/v1", "MISTRAL_API_KEY"),
        Triple("xai", "https://api.x.ai/v1", "XAI_API_KEY"),
        Triple("cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY")
    )

    return candidates
        .filter { (_, _, keyEnv) ->
            Platform.getenv(keyEnv)?.isNotEmpty() == true
        }
        .map { (name, baseUrl, keyEnv) ->
            ProviderDef(name = name, baseUrl = baseUrl, keyEnv = keyEnv)
        }
}

/**
 * Return provider info by name, or null if unknown.
 */
fun getProvider(name: String): ProviderDef? {
    val candidates = listOf(
        Triple("anthropic", "https://api.anthropic.com/v1", "ANTHROPIC_API_KEY"),
        Triple("openai", "https://api.openai.com/v1", "OPENAI_API_KEY"),
        Triple("google", "https://generativelanguage.googleapis.com/v1beta/openai", "GOOGLE_API_KEY"),
        Triple("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "GOOGLE_API_KEY"),
        Triple("groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY"),
        Triple("openrouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY"),
        Triple("mistral", "https://api.mistral.ai/v1", "MISTRAL_API_KEY"),
        Triple("xai", "https://api.x.ai/v1", "XAI_API_KEY"),
        Triple("grok", "https://api.x.ai/v1", "XAI_API_KEY"),
        Triple("cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY"),
        Triple("ollama", "http://localhost:11434/v1", ""),
        Triple("lmstudio", "http://localhost:1234/v1", "")
    )

    return candidates
        .find { (n, _, _) -> n == name }
        ?.let { (n, u, k) -> ProviderDef(name = n, baseUrl = u, keyEnv = k) }
}

/**
 * True if the key looks like a real secret (non-empty, not a placeholder).
 */
fun isRealKeyPub(key: String): Boolean {
    return key.isNotEmpty() &&
        key != "sk-placeholder" &&
        key != "YOUR_API_KEY" &&
        !key.startsWith("sk-test")
}

/**
 * Return quota usage as (provider, used_tokens, remaining_tokens, confidence).
 */
fun allProviderQuotas(): List<Triple<String, ULong, ULong, Double>> {
    return discoverProviders().map { p -> Triple(p.name, 0uL, ULong.MAX_VALUE, 1.0) }
}

/**
 * Platform abstraction for environment variable access.
 * On JVM this uses System.getenv; on JS/Native we use expect/actual.
 */
expect object Platform {
    fun getenv(key: String): String?
}
