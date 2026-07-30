package borg.trikeshed.modelmux

data class ModelResponse(
    val content: String,
    val usage: ModelUsage,
    val providerId: String
)

/**
 * Provenance receipt for one real modelmux call. Carries the assessment
 * fingerprint (requestHash), the session that produced it, the latency,
 * the cached payload, and the cache hit/miss disposition. Appended to
 * a `ModelCallLeaf` (see jvmMain) so the assessment and the receipt
 * survive the in-memory board reload.
 *
 * `assessmentId` is the optional `ModelCallDescriptor.id` (when the call
 * originated from the kanban dispatcher); `requestHash` is the canonical
 * fingerprint of the actual outbound request. Together they close the
 * intent → response loop: every receipt points back to its assessment.
 */
data class ModelResponseReceipt(
    val receiptId: String,                 // mrec-<uuid> (Monotonic filename-safe id)
    val modelId: String,                   // routed model
    val providerId: String,                // provider card id
    val requestHash: String,               // hashCode of encoded request body
    val assessmentId: String? = null,      // ModelCallDescriptor.id when dispatched from kanban
    val sessionId: String? = null,         // LlmSession.sessionId when LlmSession is id'd
    val action: String,                    // "chat" | "stream" | "embed"
    val httpStatus: Int,                   // 2xx → 200..299
    val latencyMs: Long,                   // wall clock from request to response
    val inputTokens: Int,                  // from AcpUsage.usage.prompt_tokens
    val outputTokens: Int,                 // from AcpUsage.usage.completion_tokens
    val cachedHit: Boolean,                 // true if served from MuxReactorElement cache
    val errorClass: String? = null,         // exception class name on failure
    val errorMessage: String? = null,       // exception message (truncated 500)
    val capturedAt: Long,                  // epoch ms when the receipt was minted
) {
    fun toJsonLine(): String {
        fun esc(s: String?): String =
            s?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.replace("\n", "\\n") ?: ""
        return buildString {
            append('{')
            append("\"receiptId\":\"").append(esc(receiptId)).append('"')
            append(",\"modelId\":\"").append(esc(modelId)).append('"')
            append(",\"providerId\":\"").append(esc(providerId)).append('"')
            append(",\"requestHash\":\"").append(esc(requestHash)).append('"')
            if (assessmentId != null) { append(",\"assessmentId\":\"").append(esc(assessmentId)).append('"') }
            if (sessionId != null) { append(",\"sessionId\":\"").append(esc(sessionId)).append('"') }
            append(",\"action\":\"").append(esc(action)).append('"')
            append(",\"httpStatus\":").append(httpStatus)
            append(",\"latencyMs\":").append(latencyMs)
            append(",\"inputTokens\":").append(inputTokens)
            append(",\"outputTokens\":").append(outputTokens)
            append(",\"cachedHit\":").append(cachedHit)
            if (errorClass != null) { append(",\"errorClass\":\"").append(esc(errorClass)).append('"') }
            if (errorMessage != null) { append(",\"errorMessage\":\"").append(esc(errorMessage)).append('"') }
            append(",\"capturedAt\":").append(capturedAt)
            append('}')
        }
    }

    companion object {
        fun mint(
            modelId: String,
            providerId: String,
            requestHash: String,
            action: String,
            httpStatus: Int,
            latencyMs: Long,
            inputTokens: Int = 0,
            outputTokens: Int = 0,
            cachedHit: Boolean = false,
            assessmentId: String? = null,
            sessionId: String? = null,
            error: Throwable? = null,
        ): ModelResponseReceipt = ModelResponseReceipt(
            receiptId = "mrec-${kotlin.random.Random.nextLong().toString(16)}",
            modelId = modelId,
            providerId = providerId,
            requestHash = requestHash,
            assessmentId = assessmentId,
            sessionId = sessionId,
            action = action,
            httpStatus = httpStatus,
            latencyMs = latencyMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedHit = cachedHit,
            errorClass = error?.let { it::class.simpleName },
            errorMessage = error?.message?.take(500),
            capturedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
    }
}
