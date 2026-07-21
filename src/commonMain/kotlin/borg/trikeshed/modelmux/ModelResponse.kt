package borg.trikeshed.modelmux

data class ModelResponse(
    val content: String,
    val usage: ModelUsage,
    val providerId: String
)
