@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.execute
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ByteOrder
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider

public abstract class SocketChannel : AbstractSelectableChannel, ByteChannel, ScatteringByteChannel, GatheringByteChannel, NetworkChannel {
    constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): SocketChannel
    public abstract override fun <T> setOption(option: String, value: T): SocketChannel
    public abstract fun shutdownInput(): SocketChannel
    public abstract fun shutdownOutput(): SocketChannel
    public abstract fun isConnected(): Boolean
    public abstract fun isConnectionPending(): Boolean
    public abstract fun connect(address: String): Boolean
    public abstract fun finishConnect(): Boolean
    /** The peer's address, or null before connection; a closed channel throws. */
    public abstract fun getRemoteAddress(): String?
    public abstract override fun read(dst: ByteBuffer): Int
    public abstract override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteBuffer>): Long
    public abstract override fun write(src: ByteBuffer): Int
    public abstract override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteBuffer>): Long
    public abstract override fun getLocalAddress(): String

    companion object {
        fun open(): SocketChannel = openWithProtocolFamily("INET")

        /** [protocolFamily] names a java.net.StandardProtocolFamily: INET or INET6. */
        fun openWithProtocolFamily(protocolFamily: String): SocketChannel {
            val channel = UringChannels.open(4)
            return UringSocketChannel(File.fromFd(stream(channel, protocolFamily)), channel)
        }

        fun openWithRemote(address: String): SocketChannel {
            val ch = openWithProtocolFamily(if (address.startsWith('[')) "INET6" else "INET")
            try {
                ch.connect(address)
            } catch (failure: Throwable) {
                ch.close()
                throw failure
            }
            return ch
        }
    }
}

/**
 * A stream socket on its own ring. CONNECT settles once the connection is made or refused, in either mode. READ and
 * WRITE meet a non-blocking socket: non-blocking mode answers 0 when they would block, blocking mode waits on POLL_ADD
 * and retries; no request outlives the call that made it.
 */
internal class UringSocketChannel(
    private val file: File,
    private val channel: FunctionalUringFacade,
    private var connected: Boolean = false,
) : SocketChannel(SelectorProvider.provider()) {
    private var nextToken: Long = 1
    private var open: Boolean = true
    private var blocking: Boolean = true
    private val lock = Any()

    private fun ensureConnected() {
        if (!open) throw ClosedChannelException()
        if (!connected) throw NotYetConnectedException()
    }

    override fun isConnected(): Boolean = open && connected
    // CONNECT settles in the submitting call; this adapter retains no unfinished CONNECT between calls.
    override fun isConnectionPending(): Boolean = false

    override fun finishConnect(): Boolean {
        if (!open) throw ClosedChannelException()
        if (!connected) throw NoConnectionPendingException()
        return true
    }

    /** [submission]'s result through this channel's ring. */
    private fun io(submission: UringSubmission): Int {
        if (!open) throw ClosedChannelException()
        return channel.execute(submission.copy(userData = nextToken++))
    }

    /** Blocks until the socket reports [mask] (or an error or hangup) through POLL_ADD. */
    private fun await(mask: Int) {
        val ready = io(Submissions.pollAdd(file.id, mask, 0))
        if (ready < 0) throw IOException("poll: $ready")
    }

    override fun begin() {}
    override fun end(completed: Boolean) {}
    override fun provider(): SelectorProvider = SelectorProvider.provider()
    override fun isRegistered(): Boolean = false
    override fun keyFor(sel: Selector): SelectionKey = throw IllegalStateException("not registered")
    override fun register(sel: Selector, ops: Int, att: Any): SelectionKey = throw UnsupportedOperationException("selector not supported")
    override fun register(sel: Selector, ops: Int): SelectionKey = register(sel, ops, Unit)
    override fun isBlocking(): Boolean = blocking
    override fun blockingLock(): Any = lock
    override fun configureBlocking(block: Boolean): SelectableChannel = also { blocking = block }
    override fun validOps(): Int = SelectionKey.OP_READ or SelectionKey.OP_WRITE or SelectionKey.OP_CONNECT
    override fun bind(address: String): SocketChannel {
        val bound = io(Submissions.bind(file.id, sockaddr(address), 0))
        if (bound < 0) throw IOException("bind $address: $bound")
        return this
    }
    /** TCP_NODELAY, a boolean, through the SETSOCKOPT socket command. */
    override fun <T> setOption(option: String, value: T): SocketChannel {
        if (option != "TCP_NODELAY") throw UnsupportedOperationException("'$option' not supported")
        val flag = ByteBuffer(4).order(ByteOrder.nativeOrder()).putInt(0, if (value == true) 1 else 0).array()
        val set = io(Submissions.setsockopt(file.id, UringOp.IPPROTO_TCP, UringOp.TCP_NODELAY, flag, 0))
        if (set < 0) throw IOException("setsockopt $option: $set")
        return this
    }
    override fun <T> getOption(option: String): T = throw UnsupportedOperationException("options not supported")
    override fun supportedOptions(): Set<String> = setOf("TCP_NODELAY")

    override fun shutdownInput(): SocketChannel = shutdown(0)
    override fun shutdownOutput(): SocketChannel = shutdown(1)

    private fun shutdown(how: Int): SocketChannel {
        ensureConnected()
        val result = io(Submissions.shutdown(file.id, how, 0))
        if (result < 0) throw IOException("shutdown: $result")
        return this
    }

    override fun connect(address: String): Boolean {
        if (!open) throw ClosedChannelException()
        if (connected) throw AlreadyConnectedException()
        val encoded = sockaddr(address)
        try {
            val result = io(Submissions.connect(file.id, encoded, 0))
            if (result < 0) throw IOException("connect $address: $result")
            connected = true
            return true
        } catch (failure: IOException) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Bytes read into [dst]; -1 at end of stream, 0 when non-blocking and none are ready. */
    override fun read(dst: ByteBuffer): Int {
        ensureConnected()
        while (true) {
            val count = io(Submissions.read(file.id, 0, dst.remaining(), -1, 0).copy(buffer = dst))
            if (count > 0) return count
            if (count == 0) return if (dst.hasRemaining()) -1 else 0
            if (count != -11) throw IOException("read: $count")
            if (!blocking) return 0
            await(UringOp.POLLIN)
        }
    }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        ensureConnected()
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(dsts.size)) { val n = read(dsts[i]); if (n < 0) return if (total == 0L) -1 else total; total += n }
        return total
    }

    override fun read(dsts: Array<out ByteBuffer>): Long = read(dsts, 0, dsts.size)

    /** Bytes written from [src]; 0 when non-blocking and the socket buffer is full. */
    override fun write(src: ByteBuffer): Int {
        ensureConnected()
        while (true) {
            val count = io(Submissions.write(file.id, 0, src.remaining(), -1, 0).copy(buffer = src))
            if (count >= 0) return count
            if (count != -11) throw IOException("write: $count")
            if (!blocking) return 0
            await(UringOp.POLLOUT)
        }
    }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        ensureConnected()
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(srcs.size)) { val n = write(srcs[i]); if (n < 0) return if (total == 0L) -1 else total; total += n }
        return total
    }

    override fun write(srcs: Array<out ByteBuffer>): Long = write(srcs, 0, srcs.size)

    /** The local address, `a.b.c.d:port` or `[ipv6]:port`, from the GETSOCKNAME socket command. */
    override fun getLocalAddress(): String {
        val address = ByteBuffer(128)
        val named = io(Submissions.getsockname(file.id, address, false, 0))
        if (named < 0) throw IOException("getsockname: $named")
        return sockaddrText(address.array())
    }

    override fun getRemoteAddress(): String? {
        if (!open) throw ClosedChannelException()
        if (!connected) return null
        val address = ByteBuffer(128)
        val named = io(Submissions.getsockname(file.id, address, true, 0))
        if (named < 0) throw IOException("getpeername: $named")
        return sockaddrText(address.array())
    }

    override fun close() {
        if (!open) return
        try {
            val closed = io(Submissions.close(file.id, 0))
            if (closed < 0) throw IOException("close: $closed")
        } finally {
            open = false
            channel.closeNow()
        }
    }

    override fun isOpen(): Boolean = open
    override fun implCloseSelectableChannel() = close()
    override fun implConfigureBlocking(block: Boolean) { blocking = block }
}
