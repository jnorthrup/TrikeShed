package borg.trikeshed.modelmux

data class Prompt(
    val messages: List<PromptMessage>,
    val modelId: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024
)
