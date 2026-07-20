package borg.trikeshed.reactor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

sealed class ChannelResponse {
    data class Ok(val payload: Payload) : ChannelResponse()
    data class Err(val error: ReactorError) : ChannelResponse()
    object NoOp : ChannelResponse()
}

fun ChannelResponse.toJoin(): Join<Byte, Payload> = when (this) {
    is ChannelResponse.Ok -> 0.toByte() j (if (payload.isEmpty()) ByteArray(0) else ByteArray(payload.size) { payload[it % payload.size] })
    is ChannelResponse.Err -> 1.toByte() j ByteArray(0)
    ChannelResponse.NoOp -> 2.toByte() j ByteArray(0)
}
