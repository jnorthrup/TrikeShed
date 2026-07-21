package borg.trikeshed.modelmux

sealed class PromptMessage {
    abstract val content: String

    data class User(override val content: String) : PromptMessage()
    data class Assistant(override val content: String) : PromptMessage()
    data class System(override val content: String) : PromptMessage()
}
