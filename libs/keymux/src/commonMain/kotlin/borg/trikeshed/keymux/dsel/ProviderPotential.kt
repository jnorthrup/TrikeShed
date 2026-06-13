package borg.trikeshed.keymux.dsel

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * Represents a provider with quota and priority information.
 * Used for model serving provider selection with quota management.
 */
@Serializable
data class ProviderPotential(
    val name: String,
    val availableTokens: Int,
    val priority: Byte,
    val costPerMillion: Double,
    val isFree: Boolean,
    val freeQuota: FreeQuotaConfig? = null,
    val quotaTimeframe: QuotaTimeframe? = null,
    val rateLimit: RateLimitConfig? = null,
) {
    companion object {
        fun new(
            name: String,
            availableTokens: Int,
            priority: Byte,
            costPerMillion: Double,
            isFree: Boolean,
        ): ProviderPotential {
            return ProviderPotential(
                name = name,
                availableTokens = availableTokens,
                priority = priority,
                costPerMillion = costPerMillion,
                isFree = isFree,
            )
        }
    }

    fun withFreeQuota(
        dailyTokens: Int,
        monthlyTokens: Int,
        resetHour: Byte,
    ): ProviderPotential {
        return copy(
            freeQuota = FreeQuotaConfig(
                dailyTokens = dailyTokens,
                monthlyTokens = monthlyTokens,
                resetHour = resetHour,
                resetDayOfMonth = null,
            )
        )
    }

    fun withTimeframeQuota(
        timeframe: TimeframeType,
        limit: Int,
        resetTimestamp: Long,
    ): ProviderPotential {
        return copy(
            quotaTimeframe = QuotaTimeframe(
                timeframeType = timeframe,
                quotaLimit = limit,
                currentUsage = 0,
                resetTimestamp = resetTimestamp,
            )
        )
    }

    fun withRateLimit(
        perMinute: Long,
        perHour: Long,
        perDay: Long,
        burst: Long,
    ): ProviderPotential {
        return copy(
            rateLimit = RateLimitConfig(
                requestsPerMinute = perMinute,
                requestsPerHour = perHour,
                requestsPerDay = perDay,
                burstLimit = burst,
            )
        )
    }

    /**
     * Calculate cost for given token count.
     */
    fun calculateCost(tokens: Int): Double {
        return if (isFree) 0.0 else (tokens.toDouble() * costPerMillion) / 1_000_000.0
    }

    /**
     * Check if provider can handle the request considering free quotas.
     */
    fun canHandle(tokens: Int): Boolean {
        if (tokens > availableTokens) return false

        freeQuota?.let { fq ->
            return tokens <= fq.dailyTokens
        }

        quotaTimeframe?.let { qt ->
            return (qt.currentUsage + tokens) <= qt.quotaLimit
        }

        return true
    }

    /**
     * Get priority score (lower is better).
     * Free providers get bonus priority.
     */
    fun getPriorityScore(): Byte {
        return if (isFree) priority.dec().coerceAtLeast(0) else priority
    }

    /**
     * Check if rate limited.
     */
    fun isRateLimited(currentRequests: Long, timeframe: String): Boolean {
        return rateLimit?.let { limit ->
            when (timeframe) {
                "minute" -> currentRequests > limit.requestsPerMinute
                "hour" -> currentRequests > limit.requestsPerHour
                "day" -> currentRequests > limit.requestsPerDay
                else -> false
            }
        } ?: false
    }
}

/**
 * Free quota configuration for providers with daily/monthly limits.
 */
@Serializable
data class FreeQuotaConfig(
    val dailyTokens: Int,
    val monthlyTokens: Int,
    val resetHour: Byte,                 // 0-23
    val resetDayOfMonth: Byte? = null,   // 1-28 for monthly resets
)

/**
 * Timeframe-based quota configuration.
 */
@Serializable
data class QuotaTimeframe(
    val timeframeType: TimeframeType,
    val quotaLimit: Int,
    val currentUsage: Int,
    val resetTimestamp: Long,
)

/**
 * Type of timeframe for quota management.
 */
@Serializable
enum class TimeframeType {
    HOURLY,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

/**
 * Rate limiting configuration.
 */
@Serializable
data class RateLimitConfig(
    val requestsPerMinute: Long,
    val requestsPerHour: Long,
    val requestsPerDay: Long,
    val burstLimit: Long,
)
