package borg.trikeshed.sctp

import kotlinx.coroutines.channels.Channel

class SctpBoundedStream(val capacity: Int = 10) {
    init {
        require(capacity > 0) { "Capacity must be positive" }
    }

    private val channel = Channel<ByteArray>(capacity)

    suspend fun enqueue(data: ByteArray) {
        channel.send(data)
    }

    suspend fun dequeue(): ByteArray {
        return channel.receive()
    }
}
