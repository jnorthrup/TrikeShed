package borg.trikeshed.modelmux

data class ModelUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int = promptTokens + completionTokens
)
