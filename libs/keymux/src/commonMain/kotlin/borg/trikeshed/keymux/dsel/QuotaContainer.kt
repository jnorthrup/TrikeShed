package borg.trikeshed.keymux.dsel

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provider-specific quota tracking for API token ledger.
 */
@Serializable
data class ProviderTokenLedger(
    val providerName: String,
    val totalTokensUsed: Long = 0L,
    val tokensUsedToday: Long = 0L,
    val tokensUsedThisHour: Long = 0L,
    val lastApiCheck: Long? = null,
    val apiStatus: ProviderApiStatus = ProviderApiStatus.UNKNOWN,
    val vagueQuotaRemaining: VagueQuota? = null,
)

/**
 * Provider API status.
 */
@Serializable
enum class ProviderApiStatus {
    HEALTHY,
    DEGRADED,
    RATE_LIMITED,
    AUTHENTICATION_FAILED,
    UNKNOWN,
}

/**
 * Vague quota estimate when exact numbers aren't available.
 */
@Serializable
data class VagueQuota(
    val estimatedRemaining: Long,
    val confidence: Double, // 0.0 to 1.0
    val lastUpdated: Long,
    val source: QuotaSource,
)

/**
 * Source of quota information.
 */
@Serializable
enum class QuotaSource {
    API_DIRECT,
    ESTIMATED_FROM_USAGE,
    MANUAL_CONFIGURATION,
    UNKNOWN,
}

/**
 * Container for managing quota across multiple providers.
 */
@Serializable
data class QuotaContainer(
    val name: String,
    val providers: Map<String, ProviderPotential> = emptyMap(),
    val totalQuota: Int = 0,
    val usedQuota: Int = 0,
) {
    companion object {
        fun new(name: String): QuotaContainer {
            return QuotaContainer(name = name)
        }
    }

    fun addProvider(
        name: String,
        tokens: Int,
        priority: Byte,
        costPerMillion: Double,
        isFree: Boolean,
    ): QuotaContainer {
        val provider = ProviderPotential.new(name, tokens, priority, costPerMillion, isFree)
        return addProviderFromStruct(provider)
    }

    fun addProviderFromStruct(provider: ProviderPotential): QuotaContainer {
        return copy(
            providers = providers + (provider.name to provider),
            totalQuota = totalQuota + provider.availableTokens,
        )
    }

    fun addFreeProvider(
        name: String,
        tokens: Int,
        priority: Byte,
        dailyTokens: Int,
        monthlyTokens: Int,
        resetHour: Byte,
    ): QuotaContainer {
        val provider = ProviderPotential.new(name, tokens, priority, 0.0, true)
            .withFreeQuota(dailyTokens, monthlyTokens, resetHour)
        return addProviderFromStruct(provider)
    }

    fun addTimeframeProvider(
        name: String,
        tokens: Int,
        priority: Byte,
        costPerMillion: Double,
        timeframe: TimeframeType,
        quotaLimit: Int,
        resetTimestamp: Long,
    ): QuotaContainer {
        val provider = ProviderPotential.new(name, tokens, priority, costPerMillion, false)
            .withTimeframeQuota(timeframe, quotaLimit, resetTimestamp)
        return addProviderFromStruct(provider)
    }

    fun canAllocate(tokens: Int): Boolean {
        val available = totalQuota - usedQuota
        return tokens <= available
    }

    fun allocate(tokens: Int): Pair<QuotaContainer, ProviderPotential?> {
        if (!canAllocate(tokens)) {
            return this to null
        }

        val bestProvider = providers.values
            .filter { it.canHandle(tokens) }
            .minByOrNull { it.getPriorityScore() }

        if (bestProvider != null) {
            return copy(usedQuota = usedQuota + tokens) to bestProvider
        } else {
            return this to null
        }
    }

    fun selectProvider(tokens: Int): ProviderPotential? {
        return providers.values
            .filter { it.canHandle(tokens) }
            .minByOrNull { it.getPriorityScore() }
    }

    fun getProvider(name: String): ProviderPotential? {
        return providers[name]
    }

    fun getProvidersByPriority(): List<ProviderPotential> {
        return providers.values.toList().sortedBy { it.getPriorityScore() }
    }
}
