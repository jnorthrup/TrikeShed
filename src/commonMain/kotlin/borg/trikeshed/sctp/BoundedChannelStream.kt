package borg.trikeshed.sctp

import kotlinx.coroutines.channels.Channel

class BoundedChannelStream(capacity: Int) {
    private val channel = Channel<ByteArray>(capacity)

    fun enqueue(data: ByteArray): Boolean {
        return channel.trySend(data).isSuccess
    }

    suspend fun dequeue(): ByteArray? {
        val res = channel.receiveCatching()
        return res.getOrNull()
    }
}
