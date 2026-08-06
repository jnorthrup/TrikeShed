package borg.trikeshed.modelmux

import borg.trikeshed.lib.Series

data class Prompt(
    val messages: Series<PromptMessage>,
    val modelId: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024
)
