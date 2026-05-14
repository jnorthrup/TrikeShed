package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/**
 * Platform channel/socket factory — replaces borg.trikeshed.userspace.Channels + ChannelImpl expect.
 *
 * io_uring submission/completion ring abstraction: prepare operations, submit batch, wait for completions.
 */
interface ChannelOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ChannelOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun openChannel(entries: Int = 256): ChannelHandle
    fun socket(domain: Int, type: Int, protocol: Int): Int

    /** Bind socket to address/port. Returns 0 on success, negative on error. */
    fun bind(fd: Int, port: Int): Int

    /** Listen for incoming connections. Returns 0 on success. */
    fun listen(fd: Int, backlog: Int = 128): Int

    /** Accept an incoming connection. Returns new fd, or -1 if none pending. */
    fun accept(fd: Int): Int

    /** Connect to remote host:port. Returns 0 on success, negative on error. */
    fun connect(fd: Int, host: CharSequence, port: Int): Int

    interface ChannelHandle {
        val id: Int
        fun read(buffer: ByteBuffer, offset: Long): Int
        fun write(buffer: ByteBuffer, offset: Long): Int
        fun readv(fd: Int, buffer: ByteBuffer, userData: Long = 0L): Int
        fun writev(fd: Int, buffer: ByteBuffer, userData: Long = 0L): Int
        fun prepAccept(serverFd: Int, userData: Long = 0L): Int
        fun submit(): Int
        fun wait(minComplete: Int = 1): List<ChannelResult>
    }

    /** Close a file/socket descriptor. Returns 0 on success, negative on error. */
    abstract fun close(fd: Int): Int
}

data class ChannelResult(val fd: Int, val res: Int, val userData: Long)