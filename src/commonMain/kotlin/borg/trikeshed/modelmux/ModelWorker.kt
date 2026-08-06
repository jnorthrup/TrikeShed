package borg.trikeshed.modelmux

import borg.trikeshed.lib.Series

interface ModelWorker {
    suspend fun invoke(prompt: Prompt): ModelResponse
    suspend fun providers(): Series<ProviderDescriptor>
}
