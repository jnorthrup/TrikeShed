package borg.literbike.htxke

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.HashMap
import kotlin.collections.ArrayDeque

/**
 * CCEK Channels - Compile-time channelized tributaries
 *
 * Channels are the rivers connecting protocol tributaries.
 * Bound at compile time through CCEK.
 */

/**
 * Channel error types
 */
sealed class ChannelError {
    object Closed : ChannelError()
    class Full<T>(val value: T) : ChannelError()
}

/**
 * Transmit end of a CCEK channel
 */
class ChannelTx<T>(
    private val queue: MutableList<T>,
    private val mutex: Mutex,
    val capacity: Int
) {
    suspend fun send(value: T): Result<Unit> = mutex.withLock {
        if (queue.size >= capacity) {
            return@withLock Result.failure(ChannelError.Full(value) as Throwable)
        }
        queue.add(value)
        Result.success(Unit)
    }

    fun trySend(value: T): Result<Unit> {
        if (queue.size >= capacity) {
            return Result.failure(ChannelError.Full(value) as Throwable)
        }
        queue.add(value)
        return Result.success(Unit)
    }
}

/**
 * Receive end of a CCEK channel
 */
class ChannelRx<T>(
    private val queue: MutableList<T>,
    private val mutex: Mutex,
    val capacity: Int
) {
    suspend fun receive(): T? = mutex.withLock {
        if (queue.isEmpty()) null else queue.removeAt(0)
    }

    fun tryReceive(): T? {
        return if (queue.isEmpty()) null else queue.removeAt(0)
    }
}

/**
 * CCEK Channel pair - compile-time bound
 */
class Channel<T>(capacity: Int) {
    private val queue = ArrayDeque<T>(capacity)
    private val mutex = Mutex()

    val tx = ChannelTx(queue as MutableList<T>, mutex, capacity)
    val rx = ChannelRx(queue as MutableList<T>, mutex, capacity)

    companion object {
        fun <T> create(capacity: Int): Channel<T> = Channel(capacity)
    }
}
