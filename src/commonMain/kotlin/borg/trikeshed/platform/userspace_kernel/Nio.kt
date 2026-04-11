package borg.literbike.userspace_kernel

import java.io.IOException

/**
 * Userspace non-blocking I/O abstractions
 *
 * This module provides cross-platform facades over common userspace
 * non-blocking I/O primitives: reactors, poll/select fallbacks, and
 * lightweight buffer management.
 */
object NioModule {

    /**
     * Trait representing a non-blocking channel capable of polling for readiness
     */
    interface NioChannel {
        fun pollReadable(timeoutMs: Long = 0): Result<Boolean>
        fun pollWritable(timeoutMs: Long = 0): Result<Boolean>
        fun tryRead(buf: ByteArray): Result<Int>
        fun tryWrite(buf: ByteArray): Result<Int>
    }

    /**
     * A reactor for managing non-blocking I/O operations
     */
    interface Reactor {
        fun register(channel: NioChannel): Result<Int>
        fun unregister(id: Int): Result<Unit>
        fun tick(maxWaitMs: Long = 0): Result<Int>
        fun channelCount(): Int
    }

    /**
     * Simple single-threaded reactor implementation
     */
    class SimpleReactor {
        private val channels = mutableListOf<NioChannel>()

        companion object {
            fun create(): SimpleReactor = SimpleReactor()
        }

        fun processReady(): Result<Int> = runCatching {
            var readyCount = 0
            for (channel in channels) {
                if (channel.pollReadable(0).getOrNull() == true) readyCount++
                if (channel.pollWritable(0).getOrNull() == true) readyCount++
            }
            readyCount
        }

        fun register(channel: NioChannel): Result<Int> {
            val id = channels.size
            channels.add(channel)
            return Result.success(id)
        }

        fun unregister(id: Int): Result<Unit> {
            return if (id < channels.size) {
                channels.removeAt(id)
                Result.success(Unit)
            } else {
                Result.failure(IOException("Invalid channel ID"))
            }
        }

        fun tick(maxWaitMs: Long = 0): Result<Int> {
            if (maxWaitMs > 0) {
                Thread.sleep(minOf(maxWaitMs, 1))
            }
            return processReady()
        }

        fun channelCount(): Int = channels.size
    }

    /**
     * Future that waits for a channel to become readable
     */
    class ReadableFuture<T : NioChannel>(private val channel: T) {
        companion object {
            fun <T : NioChannel> create(channel: T): ReadableFuture<T> = ReadableFuture(channel)
        }

        fun poll(): Result<Boolean> = channel.pollReadable(0)
    }

    /**
     * Future that waits for a channel to become writable
     */
    class WritableFuture<T : NioChannel>(private val channel: T) {
        companion object {
            fun <T : NioChannel> create(channel: T): WritableFuture<T> = WritableFuture(channel)
        }

        fun poll(): Result<Boolean> = channel.pollWritable(0)
    }
}
