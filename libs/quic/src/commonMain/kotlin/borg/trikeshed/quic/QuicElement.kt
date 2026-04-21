package borg.trikeshed.quic

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

data class QuicConfig(
    val alpn: List<String> = emptyList(),
    val maxIdleTimeoutMs: Long = 30000,
    val maxUdpPayloadSize: Int = 1350,
)

sealed class QuicError(message: String) : RuntimeException(message) {
    class ConnectionFailed(host: String, port: Int) : QuicError("Failed to connect to $host:$port")
    class ProtocolViolation(details: String) : QuicError(details)
    class Closed : QuicError("QUIC element is closed")
}

data class QuicStream(val id: Long)

val QuicKey: AsyncContextKey<QuicElement> = QuicElement.Key

suspend fun openQuicElement(config: QuicConfig = QuicConfig()): AsyncContextElement =
    QuicElement(config).also { it.open() }

class QuicElement(
    val config: QuicConfig = QuicConfig(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<QuicElement>()

    override val key: AsyncContextKey<QuicElement>
        get() = Key

    override suspend fun open() {
        requireState(ElementState.CREATED)
        state = ElementState.OPEN
    }

    override suspend fun close() {
        requireState(ElementState.OPEN)
        state = ElementState.CLOSING
        state = ElementState.CLOSED
    }

    suspend fun connect(host: String, port: Int): QuicStream {
        requireState(ElementState.OPEN)
        return QuicStream(1)
    }
}