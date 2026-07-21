package borg.trikeshed.modelmux

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val costPer1kTokens: Double,
    val averageLatencyMs: Long
)
