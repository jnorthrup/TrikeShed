package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/**
 * Platform channel/socket factory — replaces [borg.trikeshed.userspace.nio.channels.UringChannels] + ChannelImpl expect.
 *
 * io_uring submission/completion ring abstraction. The socket lifecycle is
 * submission-only: every operation prepares an SQE on a [ChannelHandle] and
 * reports through its CQE — `IORING_OP_SOCKET` (res = fd), `IORING_OP_BIND` /
 * `IORING_OP_LISTEN` / `IORING_OP_CONNECT` (res = 0 or -errno), `IORING_OP_ACCEPT`
 * (res = fd), `IORING_OP_CLOSE` (res = 0). No synchronous network syscall lives
 * in this interface; the backend executes the ring.
 */
interface ChannelOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ChannelOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun openChannel(entries: Int = 256): ChannelHandle

    /**
     * Resolve a host to IPv4 octets for a CONNECT/BIND SQE sockaddr.
     * DNS has no ring opcode; resolution is control-plane, the address it
     * yields rides the SQE. Returns null when the host cannot be resolved.
     */
    fun resolve(host: String): ByteArray? = null

    interface ChannelHandle {
        val id: Int
        /** File read at offset (pread). */
        fun read(buffer: ByteBuffer, offset: Long): Int
        /** File write at offset (pwrite). */
        fun write(buffer: ByteBuffer, offset: Long): Int
        /** Async socket read — queues an SQE; userData echoed back in ChannelResult. */
        fun readv(fd: Int, buffer: ByteBuffer, userData: Long = 0L): Int = -1
        /** Async socket write — queues an SQE; userData echoed back in ChannelResult. */
        fun writev(fd: Int, buffer: ByteBuffer, userData: Long = 0L): Int = -1
        /** IORING_OP_ACCEPT — queues an accept SQE; CQE res = new fd or -errno. */
        fun prepAccept(serverFd: Int, userData: Long = 0L): Int = -1
        /** IORING_OP_SOCKET — queues a socket-creation SQE; CQE res = new fd or -errno. */
        fun prepSocket(domain: Int, type: Int, protocol: Int, userData: Long = 0L): Int = -1
        /** IORING_OP_BIND — queues a bind SQE; [address] is the encoded sockaddr; CQE res = 0 or -errno. */
        fun prepBind(fd: Int, address: ByteBuffer, userData: Long = 0L): Int = -1
        /** IORING_OP_LISTEN — queues a listen SQE; CQE res = 0 or -errno. */
        fun prepListen(fd: Int, backlog: Int = 128, userData: Long = 0L): Int = -1
        /** IORING_OP_CONNECT — queues a connect SQE; [address] is the encoded sockaddr; CQE res = 0 or -errno. */
        fun prepConnect(fd: Int, address: ByteBuffer, userData: Long = 0L): Int = -1
        /** IORING_OP_CLOSE — queues a close SQE; CQE res = 0 or -errno. */
        fun prepClose(fd: Int, userData: Long = 0L): Int = -1
        /** Async UDP sendmsg — queues a SENDMSG SQE with msghdr. */
        fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long = 0L): Int = -1
        /** Async UDP recvmsg — queues a RECVMSG SQE with msghdr. */
        fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long = 0L): Int = -1
        /** Settle admitted requests and release the owned submission backend. */
        fun close() {}
        fun submit(): Int
        fun wait(minComplete: Int = 1): List<ChannelResult>
    }
}

data class ChannelResult(val fd: Int, val res: Int, val userData: Long)
