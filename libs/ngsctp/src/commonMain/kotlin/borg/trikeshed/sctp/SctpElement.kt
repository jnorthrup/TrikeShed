package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.context.StreamTransport
import kotlinx.coroutines.channels.Channel

enum class SctpChunkType { DATA, INIT, INIT_ACK, SACK, HEARTBEAT, COOKIE_ECHO, COOKIE_ACK }

enum class SctpState {
    CLOSED,
    COOKIE_WAIT,
    COOKIE_ECHOED,
    ESTABLISHED,
    SHUTDOWN_PENDING,
    SHUTDOWN_SENT,
    SHUTDOWN_RECEIVED,
    SHUTDOWN_ACK_SENT,
}

sealed class SctpError(message: String) : RuntimeException(message) {
    class BindFailed(details: String) : SctpError(details)
    class ConnectFailed(details: String) : SctpError(details)
    class Closed : SctpError("SCTP element is closed")
}

data class SctpAssociation(val associationId: Long, val state: SctpState)

val SctpKey: AsyncContextKey<SctpElement> = SctpElement.Key

suspend fun openSctpElement(): SctpElement =
    SctpElement().also { it.open() }

class SctpElement(
    private val streams: MutableMap<Int, StreamHandle> = mutableMapOf(),
) : AsyncContextElement(), StreamTransport {
    companion object Key : AsyncContextKey<SctpElement>("SctpKey", 1L shl 3)

    override val key: AsyncContextKey<SctpElement>
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

    suspend fun bind(port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        return SctpAssociation(associationId = port.toLong(), state = SctpState.CLOSED)
    }

    suspend fun connect(host: String, port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        return SctpAssociation(associationId = (host.hashCode().toLong() shl 32) xor port.toLong(), state = SctpState.ESTABLISHED)
    }
}
