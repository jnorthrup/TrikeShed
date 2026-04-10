package borg.trikeshed.platform.concurrency

import kotlinx.coroutines.channels.Channel as KotlinChannel
import kotlinx.coroutines.channels.ChannelCapacity as KotlinChannelCapacity
import kotlinx.coroutines.CancellationException

/**
 * Channel primitives using CCEK pattern
 *
 * Minimal channel implementation for message passing between jobs.
 */

/**
 * Send error types
 */
sealed class SendError<T> {
    data class Closed<T>(val value: T) : SendError<T>()
    data class Full<T>(val value: T) : SendError<T>()

    fun intoInner(): T = when (this) {
        is Closed -> value
        is Full -> value
    }
}

/**
 * Receive error types
 */
sealed class RecvError {
    object Empty : RecvError()
    object Closed : RecvError()
}

/**
 * Channel capacity
 */
sealed class ChannelCapacity {
    object Unbounded : ChannelCapacity()
    data class Buffered(val capacity: Int) : ChannelCapacity()
    object Rendezvous : ChannelCapacity()

    fun toKotlinCapacity(): kotlinx.coroutines.channels.ChannelCapacity = when (this) {
        is Unbounded -> KotlinChannelCapacity.UNLIMITED
        is Buffered -> KotlinChannelCapacity(this.capacity)
        is Rendezvous -> KotlinChannelCapacity.RENDEZVOUS
    }
}

/**
 * Simple channel implementation wrapping Kotlin Channel
 */
class Channel<T>(private val capacity: ChannelCapacity) {
    private val delegate = KotlinChannel<T>(capacity.toKotlinCapacity())
    private var closed = false

    companion object {
        fun <T> rendezvous(): Channel<T> = Channel(ChannelCapacity.Rendezvous)
        fun <T> buffered(capacity: Int): Channel<T> = Channel(ChannelCapacity.Buffered(capacity))
        fun <T> unbounded(): Channel<T> = Channel(ChannelCapacity.Unbounded)
        fun <T> channel(): Pair<Channel<T>, Channel<T>> {
            val ch = rendezvous<T>()
            return ch to ch
        }
    }

    fun isClosed(): Boolean = closed
    fun close() { closed = true; delegate.close() }

    suspend fun send(value: T) {
        if (closed) throw SendError.Closed(value)
        delegate.send(value)
    }

    suspend fun recv(): T {
        if (closed) throw RecvError.Closed
        return delegate.receive()
    }
}
