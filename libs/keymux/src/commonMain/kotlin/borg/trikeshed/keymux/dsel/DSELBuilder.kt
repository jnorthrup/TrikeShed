package borg.trikeshed.keymux.dsel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * DSEL Builder for constructing quota containers with hierarchical prefix support.
 */
class DSELBuilder {
    private var container = QuotaContainer.new("default")
    private val prefixTransformations = mutable.MutableMap<String, MutableList<String>>()
    private val mutex = Mutex()

    fun withQuota(name: String, totalQuota: Int): DSELBuilder {
        mutex.withLock {
            container = QuotaContainer.new(name).copy(totalQuota = totalQuota)
        }
        return this
    }

    fun withProvider(
        name: String,
        tokens: Int,
        priority: Byte,
        costPerMillion: Double,
        isFree: Boolean,
    ): DSELBuilder {
        mutex.withLock {
            container = container.addProvider(name, tokens, priority, costPerMillion, isFree)
        }
        return this
    }

    fun withFreeProvider(
        name: String,
        tokens: Int,
        priority: Byte,
        dailyTokens: Int,
        monthlyTokens: Int,
        resetHour: Byte,
    ): DSELBuilder {
        mutex.withLock {
            container = container.addFreeProvider(name, tokens, priority, dailyTokens, monthlyTokens, resetHour)
        }
        return this
    }

    fun withTimeframeProvider(
        name: String,
        tokens: Int,
        priority: Byte,
        costPerMillion: Double,
        timeframe: TimeframeType,
        quotaLimit: Int,
        resetTimestamp: Long,
    ): DSELBuilder {
        mutex.withLock {
            container = container.addTimeframeProvider(name, tokens, priority, costPerMillion, timeframe, quotaLimit, resetTimestamp)
        }
        return this
    }

    fun withRateLimitedProvider(
        name: String,
        tokens: Int,
        priority: Byte,
        costPerMillion: Double,
        perMinute: Long,
        perHour: Long,
        perDay: Long,
        burst: Long,
    ): DSELBuilder {
        mutex.withLock {
            val provider = ProviderPotential.new(name, tokens, priority, costPerMillion, false)
                .withRateLimit(perMinute, perHour, perDay, burst)
            container = container.addProviderFromStruct(provider)
        }
        return this
    }

    fun withPrefixTransformation(fromPrefix: String, toPrefix: String): DSELBuilder {
        mutex.withLock {
            prefixTransformations.getOrPut(fromPrefix) { mutable.MutableList() }.add(toPrefix)
        }
        return this
    }

    fun build(): Result<QuotaContainer> {
        return mutex.withLock {
            if (container.providers.isEmpty()) {
                return Result.failure(IllegalStateException("No providers defined"))
            }
            if (container.totalQuota == 0) {
                return Result.failure(IllegalStateException("Total quota must be greater than zero"))
            }
            Result.success(container)
        }
    }

    fun buildWithHierarchicalSelector(): Result<Pair<QuotaContainer, HierarchicalModelSelector>> {
        return mutex.withLock {
            if (container.providers.isEmpty()) {
                return Result.failure(IllegalStateException("No providers defined"))
            }

            val selector = HierarchicalModelSelector(container)

            // Add prefix transformation rules from DSEL configuration
            for ((fromPrefix, toPrefixes) in prefixTransformations) {
                for (toPrefix in toPrefixes) {
                    selector.addTransformationRule(fromPrefix, toPrefix, 100)
                }
            }

            // Add common transformation rules for known bad agent concatenations
            selector.addTransformationRule("/litellm/litellm/litellm/", "/litellm/", 100)
            selector.addTransformationRule("/ccswitch/ccswitch/ccswitch/", "/ccswitch/", 90)
            selector.addTransformationRule("/openai/openai/openai/", "/openai/", 80)
            selector.addTransformationRule("/anthropic/anthropic/anthropic/", "/anthropic/", 85)

            Result.success(container to selector)
        }
    }

    fun buildWithRuleEngine(): Result<RuleEngine> {
        return buildWithHierarchicalSelector().map { (container, selector) ->
            val ruleEngine = RuleEngine()
            ruleEngine.setHierarchicalSelector(selector)
            ruleEngine.enableTokenLedger()

            // Initialize quota tracking from container providers
            for ((name, provider) in container.providers) {
                val tracking = ProviderQuotaTracking(
                    providerName = name,
                    tokensUsedToday = 0L,
                    tokensUsedThisHour = 0L,
                    estimatedRemainingQuota = provider.availableTokens.toLong(),
                    quotaConfidence = 0.9,
                    lastQuotaUpdate = currentTimestamp(),
                )
                ruleEngine.quotaTracking[name] = tracking
            }

            ruleEngine
        }
    }

    private fun currentTimestamp(): Long {
        return Instant.now().epochSeconds
    }
}
