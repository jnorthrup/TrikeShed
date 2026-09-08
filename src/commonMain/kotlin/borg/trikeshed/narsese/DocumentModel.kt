package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.modelmux.PromptMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Production binding to the existing model/key mux and its reactor transport. */
fun documentModel(brain: BrainClient, muxContext: CoroutineContext): suspend (Prompt) -> ModelResponse = { prompt ->
    val messages = List(prompt.messages.size) { i ->
        val message = prompt.messages[i]
        val role = when (message) {
            is PromptMessage.System -> "system"
            is PromptMessage.User -> "user"
            is PromptMessage.Assistant -> "assistant"
        }
        role to message.content
    }
    val requestCid = ContentId.of(CanonicalCbor.encodeMap(mapOf(
        "messages" to messages.map { mapOf("role" to it.first, "content" to it.second) },
        "model" to prompt.modelId, "maxTokens" to prompt.maxTokens, "temperature" to prompt.temperature,
    )))
    val (content, modelId) = withContext(muxContext.minusKey(Job) + currentCoroutineContext()) {
        brain.chatSeat(messages, prompt.maxTokens, prompt.temperature, requestCid.value, prompt.modelId)
    }
    val endpoint = brain.endpointSummaries().firstOrNull { it.model == modelId }
    ModelResponse(
        content,
        // chatSeat exposes content/model only. -1 is unavailable, not zero billed tokens.
        ModelUsage(-1, -1, -1),
        endpoint?.provider ?: endpoint?.name ?: "unknown",
        modelId,
    )
}
