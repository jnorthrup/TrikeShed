package borg.trikeshed.context

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlin.coroutines.CoroutineContext

/** Minimal local placeholder to restore builds when libs are included */
data class StreamHandle(
    val id: Int,
    val send: SendChannel<ByteArray>,
    val recv: ReceiveChannel<ByteArray>
)

interface StreamTransport : CoroutineContext.Element {
    suspend fun openStream(): StreamHandle
    val activeStreams: Int
}
