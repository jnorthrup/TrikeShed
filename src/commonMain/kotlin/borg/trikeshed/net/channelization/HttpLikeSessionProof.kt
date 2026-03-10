package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId

/**
 * Phase-02d: Minimal proof that one HTTP-like byte-stream session can be
 * expressed through the session/block core.
 *
 * This is a transport-agnostic, protocol-light composition helper that
 * demonstrates the smallest viable shape for an HTTP-like session using
 * only [ChannelSession], [ChannelBlock], [ChannelEnvelope], and
 * [ChannelizationProjection] primitives.
 *
 * No HTTP parser, no handler stack, no router widening, no backend-specific classes.
 */

/**
 * Minimal HTTP-like request representation.
 *
 * Captures only the structural shape needed to express an HTTP request
 * as a sequence of blocks within a channel session.
 */
data class HttpLikeRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = EmptyBody,
) {
    companion object {
        private val EmptyBody = ByteArray(0)
    }

    /** Encode request to a single block payload (minimal proof shape). */
    fun encodeToBlock(sessionId: ChannelSessionId, sequence: BlockSequence, blockId: Long = 0L): ChannelBlock {
        val headerLine = "$method $path HTTP/1.1"
        val headerBlock = buildString {
            appendLine(headerLine)
            headers.forEach { (name, value) ->
                appendLine("$name: $value")
            }
            appendLine()
        }
        val payload = if (body.isNotEmpty()) {
            headerBlock.encodeToByteArray() + body
        } else {
            headerBlock.encodeToByteArray()
        }
        return ChannelBlock(
            id = ChannelBlockId(blockId),
            session = sessionId,
            sequence = sequence,
            payload = payload,
            flags = if (body.isEmpty()) BlockFlags.EndOfStream else BlockFlags.None,
        )
    }
}

/**
 * Minimal HTTP-like response representation.
 *
 * Captures only the structural shape needed to express an HTTP response
 * as a sequence of blocks within a channel session.
 */
data class HttpLikeResponse(
    val status: Int,
    val statusText: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = EmptyBody,
) {
    /** Encode response to a single block payload (minimal proof shape). */
    fun encodeToBlock(sessionId: ChannelSessionId, sequence: BlockSequence, blockId: Long = 0L): ChannelBlock {
        val statusLine = "HTTP/1.1 $status $statusText"
        val headerBlock = buildString {
            appendLine(statusLine)
            headers.forEach { (name, value) ->
                appendLine("$name: $value")
            }
            appendLine()
        }
        val payload = if (body.isNotEmpty()) {
            headerBlock.encodeToByteArray() + body
        } else {
            headerBlock.encodeToByteArray()
        }
        return ChannelBlock(
            id = ChannelBlockId(blockId),
            session = sessionId,
            sequence = sequence,
            payload = payload,
            flags = if (body.isEmpty()) BlockFlags.EndOfStream else BlockFlags.None,
        )
    }

    companion object {
        private val EmptyBody = ByteArray(0)

        /** Decode from block payload (minimal proof shape). */
        fun decodeFromBlock(block: ChannelBlock): HttpLikeResponse {
            val lines = block.payload.decodeToString().lines()
            val statusLine = lines.firstOrNull()?.trim() ?: throw IllegalArgumentException("Empty block")
            val parts = statusLine.split(" ", limit = 3)
            val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Invalid status line")
            val statusText = parts.getOrNull(2) ?: ""
            val headerLines = lines.drop(1).takeWhile { it.contains(":") }
            val headers = headerLines.associate { line ->
                val (key, value) = line.split(":", limit = 2)
                key.trim() to value.trim()
            }
            val bodyStart = lines.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 }?.plus(1) ?: headerLines.size + 1
            val body = lines.drop(bodyStart).joinToString("\n").encodeToByteArray()
            return HttpLikeResponse(status, statusText, headers, body)
        }
    }
}

/**
 * HTTP-like session builder.
 *
 * Composes [ChannelSession], [ChannelBlock], and [ChannelEnvelope] to
 * express a single HTTP-like byte-stream session.
 */
class HttpLikeSessionBuilder(private val sessionId: ChannelSessionId) {
    private var sequenceCounter = 0L
    private var blockIdCounter = 0L
    private var _state: ChannelSessionState = ChannelSessionState.Initialized

    val state: ChannelSessionState get() = _state

    /** Build request envelope for egress. */
    fun buildRequestEnvelope(request: HttpLikeRequest): ChannelEnvelope {
        require(_state == ChannelSessionState.Active) { "Session not active" }
        val block = request.encodeToBlock(sessionId, BlockSequence(sequenceCounter++), blockIdCounter++)
        return ChannelEnvelope(
            block = block,
            direction = TransferDirection.Egress,
            protocol = ProtocolId.HTTP,
            timestamp = 0L,
        )
    }

    /** Build response envelope for ingress. */
    fun buildResponseEnvelope(response: HttpLikeResponse): ChannelEnvelope {
        require(_state == ChannelSessionState.Active) { "Session not active" }
        val block = response.encodeToBlock(sessionId, BlockSequence(sequenceCounter++), blockIdCounter++)
        return ChannelEnvelope(
            block = block,
            direction = TransferDirection.Ingress,
            protocol = ProtocolId.HTTP,
            timestamp = 0L,
        )
    }

    /** Activate session. */
    fun activate() {
        _state = ChannelSessionState.Active
    }

    /** Terminate session. */
    fun terminate() {
        _state = ChannelSessionState.Terminated
    }
}

/**
 * Create HTTP-like session from channelization projection.
 *
 * Demonstrates that an HTTP-like session can be expressed purely through
 * the session/block core by projecting a [ChannelizationPlan] and
 * instantiating the minimal session shape.
 */
fun ChannelizationProjection.toHttpLikeSession(): HttpLikeSessionBuilder {
    require(plan.protocol == ProtocolId.HTTP) { "Protocol must be HTTP" }
    require(sessionShape.semantics == ChannelSemantics.BYTE_STREAM) { "Semantics must be BYTE_STREAM" }
    return HttpLikeSessionBuilder(sessionShape.sessionId)
}

/**
 * Convenience function to create HTTP-like session directly.
 *
 * Minimal proof entry point showing one HTTP-like byte-stream session
 * expressed through the session/block core.
 */
fun createHttpLikeSession(sessionId: ChannelSessionId = ChannelSessionId("http-session")): HttpLikeSessionBuilder {
    return HttpLikeSessionBuilder(sessionId)
}
