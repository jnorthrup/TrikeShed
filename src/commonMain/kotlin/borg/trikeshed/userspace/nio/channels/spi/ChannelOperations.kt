package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/**
 * Platform channel/socket factory — replaces [borg.trikeshed.userspace.Channels] + ChannelImpl expect.
 *
 * io_uring submission/completion ring abstraction: prepare operations, submit batch, wait for completions.
 */
interface ChannelOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ChannelOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun openChannel(entries: Int = 256): ChannelHandle
    fun socket(domain: Int, type: Int, protocol: Int): Int

    interface ChannelHandle {
        val id: Int
        fun read(buffer: ByteBuffer, offset: Long): Int
        fun write(buffer: ByteBuffer, offset: Long): Int
        fun submit(): Int
        fun wait(minComplete: Int = 1): List<ChannelResult>
    }
}

data class ChannelResult(val fd: Int, val res: Int, val userData: Long)
