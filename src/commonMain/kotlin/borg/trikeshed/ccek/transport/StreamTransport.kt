package borg.trikeshed.ccek.transport

import borg.trikeshed.ccek.KeyedService
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

/** A single logical stream — independent send/receive channels backed by whichever transport is installed. */
data class StreamHandle(
    val id: Int,
    val send: SendChannel<ByteArray>,
    val recv: ReceiveChannel<ByteArray>
)

/** Transport-agnostic multi-stream abstraction. Both SCTP-style and QUIC-style streams implement this. */
interface StreamTransport : KeyedService {
    /** Open a new logical stream. */
    suspend fun openStream(): StreamHandle
    /** Count of currently open streams. */
    val activeStreams: Int
}
