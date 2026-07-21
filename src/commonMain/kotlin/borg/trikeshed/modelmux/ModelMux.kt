package borg.trikeshed.modelmux

class ModelMux(
    private val registry: ProviderRegistry,
    private val rule: SelectionRule,
) : ModelWorker {
    override suspend fun invoke(prompt: Prompt): ModelResponse {
        val provider = registry.select(rule)
        return when (provider) {
            is ModelProvider.Mock -> ModelResponse(
                content = provider.respond(prompt),
                usage = ModelUsage(promptTokens = 0, completionTokens = 0),
                providerId = "mock",
            )
            is ModelProvider.Anthropic -> ModelResponse(
                content = "[anthropic stub] ${prompt.messages.last().content}",
                usage = ModelUsage(promptTokens = 0, completionTokens = 0),
                providerId = "anthropic",
            )
            is ModelProvider.OpenAI -> ModelResponse(
                content = "[openai stub] ${prompt.messages.last().content}",
                usage = ModelUsage(promptTokens = 0, completionTokens = 0),
                providerId = "openai",
            )
            is ModelProvider.Local -> ModelResponse(
                content = "[local stub] ${prompt.messages.last().content}",
                usage = ModelUsage(promptTokens = 0, completionTokens = 0),
                providerId = "local",
            )
        }
    }
    override suspend fun providers(): List<ProviderDescriptor> = registry.descriptors()
}
