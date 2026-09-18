package borg.trikeshed.narsese

import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.j
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.modelmux.PromptMessage
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import modelmux.MuxCallContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** One real reply plus the EXACT receipt ModelMux minted for this call — never the mux's shared lastReceipt. */
data class DocumentModelAnswer(val response: ModelResponse, val receipt: ModelResponseReceipt)

/**
 * Thrown when a model call reaches ModelMux and fails; carries the failure
 * receipt so the caller can retain it, never a faked success response.
 */
class DocumentModelFailure(val receipt: ModelResponseReceipt, cause: Throwable) : Exception(cause.message, cause)

/**
 * The only doorway a document curation call may reach a model through.
 * Constructible exclusively via [through]: every invocation re-resolves
 * [MuxReactorElement.modelMux] (which enforces the OPEN/ACTIVE gate and the
 * same-[keymux.KeyMux] identity) instead of pinning a session at construction,
 * so a catalog refresh or a deferred boot-time provider is observed on the
 * next call, never frozen at admission.
 */
class DocumentModel private constructor(
    private val reactor: MuxReactorElement,
    private val timeoutMs: Long,
    private val expectedBaseUrl: String?,
    private val transport: CoroutineContext,
) {
    companion object {
        /** The sole production factory. [context] must carry the [MuxReactorElement] a call will route through. */
        fun through(
            context: CoroutineContext,
            timeoutMs: Long = HtxRequest.DEFAULT_TIMEOUT_MS,
            expectedBaseUrl: String? = null,
        ): DocumentModel = DocumentModel(
            context[MuxReactorElement.Key] ?: error("need(model): no MuxReactorElement in context"),
            timeoutMs, expectedBaseUrl, context[HtxKey] ?: EmptyCoroutineContext,
        )

        fun through(
            reactor: MuxReactorElement,
            timeoutMs: Long = HtxRequest.DEFAULT_TIMEOUT_MS,
            expectedBaseUrl: String? = null,
        ): DocumentModel = DocumentModel(reactor, timeoutMs, expectedBaseUrl, EmptyCoroutineContext)
    }

    suspend operator fun invoke(prompt: Prompt, contextId: String? = null): DocumentModelAnswer {
        reactor.keyMux() ?: error("need(key): MuxReactorElement has no KeyMux binding")
        val mux = reactor.modelMux() ?: error("need(model): MuxReactorElement has no ModelMux provider")
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
        var minted: ModelResponseReceipt? = null
        val parent = currentCoroutineContext()[MuxCallContext]
        val attribution = MuxCallContext(
            conversationId = parent?.conversationId, turnId = parent?.turnId, activity = parent?.activity,
            onReceipt = { minted = it; parent?.onReceipt?.invoke(it) }, onFinished = parent?.onFinished,
        )
        val result = withContext(transport + reactor + attribution) {
            mux.chat(
                modelId = prompt.modelId,
                messages = messages,
                contextId = contextId ?: requestCid.value,
                maxTokens = prompt.maxTokens,
                temperature = prompt.temperature,
                timeoutMs = timeoutMs,
                expectedBaseUrl = expectedBaseUrl,
                jsonOutput = glm,
                thinking = if (glm) false else null,
            )
        }
        val receipt = checkNotNull(minted) { "ModelMux completed a call without minting a receipt" }
        val failure = result.exceptionOrNull()
        if (failure != null) throw DocumentModelFailure(receipt, failure)
        val response = result.getOrThrow()
        return DocumentModelAnswer(
            ModelResponse(response.a, ModelUsage(response.b.a, response.b.b, response.b.a + response.b.b),
                receipt.providerId, receipt.modelId),
            receipt,
        )
    }
}
