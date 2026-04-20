package borg.trikeshed.userspace.concurrency

import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * Channel primitives ported from literbike.
 * Leveraging Kotlin's native [kotlinx.coroutines.channels.Channel].
 */

sealed class SendError<T>(val value: T) {
    class Closed<T>(value: T) : SendError<T>(value)
    class Full<T>(value: T) : SendError<T>(value)
}

enum class RecvError {
    Empty,
    Closed
}

enum class ChannelCapacity {
    Unbounded,
    Buffered,
    Rendezvous
}

class Channel<T>(val capacity: ChannelCapacity, val bufferSize: Int = 0) {
    private val kChannel = when (capacity) {
        ChannelCapacity.Unbounded -> KChannel<T>(KChannel.UNBOUNDED)
        ChannelCapacity.Buffered -> KChannel<T>(bufferSize)
        ChannelCapacity.Rendezvous -> KChannel<T>(KChannel.RENDEZVOUS)
    }

    fun isClosed(): Boolean = kChannel.isClosedForSend

    fun close() {
        kChannel.close()
    }

    suspend fun send(value: T): Result<Unit> {
        return try {
            kChannel.send(value)
            Result.success(Unit)
        } catch (e: ClosedSendChannelException) {
            Result.failure(e)
        }
    }

    suspend fun recv(): Result<T> {
        return try {
            val value = kChannel.receive()
            Result.success(value)
        } catch (e: ClosedReceiveChannelException) {
            Result.failure(e)
        }
    }

    companion object {
        fun <T> rendezvous() = Channel<T>(ChannelCapacity.Rendezvous)
        fun <T> buffered(capacity: Int) = Channel<T>(ChannelCapacity.Buffered, capacity)
        fun <T> unbounded() = Channel<T>(ChannelCapacity.Unbounded)
    }
}

/**
 * Create a channel pair (sender and receiver)
 * In Kotlin, the Channel object itself often serves as both.
 */
fun <T> channel(): Pair<Channel<T>, Channel<T>> {
    val ch = Channel.rendezvous<T>()
    return Pair(ch, ch)
}
