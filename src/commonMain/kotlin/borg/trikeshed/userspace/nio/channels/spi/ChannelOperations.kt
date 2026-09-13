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
    /** Settle one prepared SQE through a throwaway ring: returns the CQE res. */
    private fun settleOne(prep: (ChannelHandle) -> Int): Int {
        val handle = openChannel(4)
        try {
            if (prep(handle) < 0) return -1
            if (handle.submit() != 1) return -1
            return handle.wait(1).firstOrNull()?.res ?: -1
        } finally {
            handle.close()
        }
    }

    /** IORING_OP_SOCKET: create a socket; res = fd or -errno. */
    fun socket(domain: Int, type: Int, protocol: Int): Int = settleOne { it.prepSocket(domain, type, protocol) }

    /** IORING_OP_BIND with an encoded AF_UNIX sockaddr built from [path]. */
    fun bindUnix(fd: Int, path: String): Int {
        val bytes = sockaddrUnix(path) ?: return -22
        return settleOne { it.prepBind(fd, ByteBuffer(bytes)) }
    }

    /** IORING_OP_LISTEN. */
    fun listen(fd: Int, backlog: Int = 128): Int = settleOne { it.prepListen(fd, backlog) }

    /** IORING_OP_ACCEPT: res = new fd or -errno. */
    fun accept(fd: Int): Int = settleOne { it.prepAccept(fd) }

    /** IORING_OP_CLOSE. */
    fun close(fd: Int): Int = settleOne { it.prepClose(fd) }

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

/** Encoded AF_UNIX sockaddr (sun_path, linux shape: family u16 + 108 bytes) or null when too long. */
fun sockaddrUnix(path: String): ByteArray? {
    if (path.length > 107) return null
    val bytes = ByteArray(110)
    bytes[0] = 1
    bytes[1] = 0
    path.encodeToByteArray().copyInto(bytes, 2)
    return bytes
}

data class ChannelResult(val fd: Int, val res: Int, val userData: Long)
