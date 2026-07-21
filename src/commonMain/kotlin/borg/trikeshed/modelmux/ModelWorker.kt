package borg.trikeshed.modelmux

interface ModelWorker {
    suspend fun invoke(prompt: Prompt): ModelResponse
    suspend fun providers(): List<ProviderDescriptor>
}
