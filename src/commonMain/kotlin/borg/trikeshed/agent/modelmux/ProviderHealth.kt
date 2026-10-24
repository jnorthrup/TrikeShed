package modelmux

import kotlinx.serialization.Serializable

/**
 * Live provider health state updated only by the reactor.
 */
@Serializable
data class ProviderHealth(
    val provider: String,
    val successRate: Double,
    val recentLatencyMs: Long,
    val updatedAt: Long
)
