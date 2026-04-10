package borg.trikeshed.platform.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Userspace non-blocking I/O abstractions
 *
 * This module provides cross-platform facades over common userspace
 * non-blocking I/O primitives: reactors, poll/select fallbacks, and
 * lightweight buffer management.
 */

/**
 * Trait representing a non-blocking channel capable of polling for readiness
 */
interface NioChannel {
    /**
     * Poll for readability with an optional timeout
     */
    suspend fun pollReadable(timeout: Duration?): Result<Boolean>

    /**
     * Poll for writability with an optional timeout
     */
    suspend fun pollWritable(timeout: Duration?): Result<Boolean>

    /**
     * Try to read data without blocking
     */
    fun tryRead(buf: ByteArray): Result<Int>

    /**
     * Try to write data without blocking
     */
    fun tryWrite(buf: ByteArray): Result<Int>
}

/**
 * A reactor for managing non-blocking I/O operations
 */
interface Reactor {
    /**
     * Register a channel for monitoring
     */
    fun <T : NioChannel> register(channel: T): Result<Int>

    /**
     * Unregister a channel
     */
    fun unregister(id: Int): Result<Unit>

    /**
     * Run the reactor for a single tick
     */
    fun tick(maxWait: Duration?): Result<Int>

    /**
     * Get number of registered channels
     */
    val channelCount: Int
}

/**
 * Simple single-threaded reactor implementation
 */
class SimpleReactor : Reactor {
    private val channels = mutableListOf<NioChannel>()

    override val channelCount: Int
        get() = channels.size

    fun processReady(): Result<Int> {
        var readyCount = 0
        channels.forEach { channel ->
            if (channel.pollReadable(0.toDuration(DurationUnit.MILLISECONDS)).getOrNull() == true) {
                readyCount++
            }
            if (channel.pollWritable(0.toDuration(DurationUnit.MILLISECONDS)).getOrNull() == true) {
                readyCount++
            }
        }
        return Result.success(readyCount)
    }

    override fun register(channel: NioChannel): Result<Int> {
        val id = channels.size
        channels.add(channel)
        return Result.success(id)
    }

    override fun unregister(id: Int): Result<Unit> {
        if (id in channels.indices) {
            channels.removeAt(id)
            return Result.success(Unit)
        }
        return Result.failure(IllegalArgumentException("Invalid channel ID"))
    }

    override fun tick(maxWait: Duration?): Result<Int> {
        maxWait?.let {
            Thread.sleep(it.inWholeMilliseconds.coerceAtMost(1))
        }
        return processReady()
    }
}

/**
 * Mock channel for testing
 */
class MockChannel(
    private var readable: Boolean = false,
    private var writable: Boolean = false
) : NioChannel {
    override suspend fun pollReadable(timeout: Duration?): Result<Boolean> =
        Result.success(readable)

    override suspend fun pollWritable(timeout: Duration?): Result<Boolean> =
        Result.success(writable)

    override fun tryRead(buf: ByteArray): Result<Int> {
        return if (readable) {
            buf[0] = 42
            Result.success(1)
        } else {
            Result.failure(RuntimeException("Not readable"))
        }
    }

    override fun tryWrite(buf: ByteArray): Result<Int> {
        return if (writable) {
            Result.success(1)
        } else {
            Result.failure(RuntimeException("Not writable"))
        }
    }
}
