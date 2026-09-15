package borg.trikeshed.narsese

import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lib.j
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.modelmux.PromptMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Invoke the named model through the existing key mux/HTX path; an expected base pins its destination. */
fun documentModel(
    brain: BrainClient,
    muxContext: CoroutineContext,
    timeoutMs: Long = HtxRequest.DEFAULT_TIMEOUT_MS,
    expectedBaseUrl: String? = null,
): suspend (Prompt) -> ModelResponse = { prompt ->
    val glm = expectedBaseUrl?.startsWith("https://api.z.ai/") == true && prompt.modelId.startsWith("glm-")
    val messages = prompt.messages α { message ->
        val role = when (message) {
            is PromptMessage.System -> "system"
            is PromptMessage.User -> "user"
            is PromptMessage.Assistant -> "assistant"
        }
        role j message.content
    }
    val requestCid = ContentId.of(CanonicalCbor.encodeMap(mapOf(
        "messages" to messages.view.map { mapOf("role" to it.a, "content" to it.b) },
        "model" to prompt.modelId, "maxTokens" to prompt.maxTokens, "temperature" to prompt.temperature,
        "jsonOutput" to glm, "thinking" to if (glm) false else null,
    )))
    val response = withContext(muxContext.minusKey(Job) + currentCoroutineContext()) {
        brain.modelMux().chat(
            modelId = prompt.modelId,
            messages = messages,
            assessmentId = requestCid.value,
            maxTokens = prompt.maxTokens,
            temperature = prompt.temperature,
            timeoutMs = timeoutMs,
            expectedBaseUrl = expectedBaseUrl,
            jsonOutput = glm,
            thinking = if (glm) false else null,
        ).getOrThrow()
    }
    val endpoint = brain.endpointSummaries().firstOrNull { it.model == prompt.modelId }
    ModelResponse(
        response.a,
        ModelUsage(response.b.a, response.b.b, response.b.a + response.b.b),
        endpoint?.provider ?: endpoint?.name ?: "unknown",
        prompt.modelId,
    )
}
