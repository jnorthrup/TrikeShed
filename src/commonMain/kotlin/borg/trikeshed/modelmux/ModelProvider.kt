package borg.trikeshed.modelmux

sealed class ModelProvider {
    data object Anthropic : ModelProvider()
    data object OpenAI : ModelProvider()
    data object Local : ModelProvider()
    class Mock(val respond: (Prompt) -> String) : ModelProvider()
}
