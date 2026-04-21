package borg.trikeshed.quic

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import kotlinx.coroutines.channels.Channel

data class QuicConfig(
    val alpn: List<String> = emptyList(),
    val maxIdleTimeoutMs: Long = 30000,
    val maxUdpPayloadSize: Int = 1350,
)

val QuicKey: AsyncContextKey<QuicElement> = QuicElement.Key

suspend fun openQuicElement(config: QuicConfig = QuicConfig()): QuicElement =
    QuicElement(config).also { it.open() }

class QuicElement(
    val config: QuicConfig = QuicConfig(),
    private val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
) : AsyncContextElement(), StreamTransport {
    companion object Key : AsyncContextKey<QuicElement>("QuicKey", 1L shl 4)

    override val key: AsyncContextKey<QuicElement>
        get() = Key

    override suspend fun openStream(): StreamHandle {
        requireState(ElementState.OPEN)
        val streamId = (streams.keys.maxOrNull() ?: -1) + 1
        val streamHandle = StreamHandle(
            id = streamId,
            send = Channel(Channel.BUFFERED),
            recv = Channel(Channel.BUFFERED),
        )
        streams[streamId] = streamHandle
        return streamHandle
    }

    override val activeStreams: Int get() = streams.size

    suspend fun connect(host: String, port: Int): StreamHandle {
        requireState(ElementState.OPEN)
        return openStream()
    }
}
