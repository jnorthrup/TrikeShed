package borg.literbike.concurrency

import kotlinx.coroutines.channels.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Simplified Channel module - no tokio dependency
 * Channel-based communication for structured concurrency
 */

/**
 * Channel sender
 */
class ChannelSender<T : Any>(
    private val channel: Channel<T>
) {
    companion object {
        fun <T : Any> new(capacity: Int): ChannelSender<T> {
            return ChannelSender(Channel(capacity))
        }
    }

    suspend fun send(value: T): Result<Unit> = runCatching {
        channel.send(value)
    }

    fun trySend(value: T): Result<Unit> {
        return when (val result = channel.trySend(value)) {
            is ChannelResult.Success -> Result.success(Unit)
            is ChannelResult.Closed -> Result.failure(IllegalStateException("Channel closed"))
            is ChannelResult.Failure -> Result.failure(IllegalStateException("Channel full"))
        }
    }
}

/**
 * Channel receiver
 */
class ChannelReceiver<T : Any>(
    private val channel: Channel<T>
) {
    companion object {
        fun <T : Any> new(capacity: Int): ChannelReceiver<T> {
            return ChannelReceiver(Channel(capacity))
        }
    }

    suspend fun recv(): T? = runCatching {
        channel.receive()
    }.getOrNull()

    fun tryRecv(): T? {
        return when (val result = channel.tryReceive()) {
            is ChannelResult.Success -> result.value
            else -> null
        }
    }
}

/**
 * Create a channel pair
 */
fun <T : Any> channel(bufferSize: Int): Pair<ChannelSender<T>, ChannelReceiver<T>> {
    val ch = Channel<T>(bufferSize)
    return ChannelSender(ch) to ChannelReceiver(ch)
}

/**
 * Create a channel pair with a coroutine scope
 */
fun <T : Any> channelWithScope(
    scope: CoroutineScope,
    bufferSize: Int
): Pair<SendChannel<T>, ReceiveChannel<T>> {
    return scope.createChannel(bufferSize)
}

/**
 * Extension: create a channel in a scope
 */
fun CoroutineScope.createChannel(bufferSize: Int): Pair<SendChannel<T>, ReceiveChannel<T>> {
    val ch = Channel<T>(bufferSize)
    return ch to ch
}
