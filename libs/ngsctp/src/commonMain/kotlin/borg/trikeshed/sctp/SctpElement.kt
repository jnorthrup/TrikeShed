package borg.trikeshed.sctp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

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

suspend fun openSctpElement(): AsyncContextElement =
    SctpElement().also { it.open() }

class SctpElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<SctpElement>()

    override val key: AsyncContextKey<SctpElement>
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

    suspend fun bind(port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        return SctpAssociation(associationId = port.toLong(), state = SctpState.CLOSED)
    }

    suspend fun connect(host: String, port: Int): SctpAssociation {
        requireState(ElementState.OPEN)
        return SctpAssociation(associationId = (host.hashCode().toLong() shl 32) xor port.toLong(), state = SctpState.ESTABLISHED)
    }
}