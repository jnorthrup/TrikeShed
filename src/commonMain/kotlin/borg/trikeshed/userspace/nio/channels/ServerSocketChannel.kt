@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.execute
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ByteOrder
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider

/**
 * ServerSocketChannel wired behind UringFacade.
 *
 * bind/accept route through Channel → ChannelImpl → UringFacade.
 */
public abstract class ServerSocketChannel : AbstractSelectableChannel, NetworkChannel {
    constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): ServerSocketChannel
    public abstract fun bind(address: String, backlog: Int): ServerSocketChannel
    public abstract override fun <T> setOption(option: String, value: T): ServerSocketChannel
    /** The next connection; null in non-blocking mode while none is pending. */
    public abstract fun accept(): SocketChannel?
    public abstract override fun getLocalAddress(): String

    companion object {
        fun open(): ServerSocketChannel = open("INET")

        /** [protocolFamily] names a java.net.StandardProtocolFamily: INET or INET6. */
        fun open(protocolFamily: String): ServerSocketChannel {
            val channel = UringChannels.open(4)
            return UringServerSocketChannel(File.fromFd(stream(channel, protocolFamily)), channel)
        }
    }
}

/**
 * A SOCK_STREAM socket of [protocolFamily] made by a SOCKET SQE on [channel], non-blocking and close-on-exec as every
 * socket of the facade's channels is: their blocking mode waits on POLL_ADD. A refusal closes [channel].
 */
internal fun stream(channel: FunctionalUringFacade, protocolFamily: String): Int {
    val domain = when (protocolFamily) {
        "INET" -> SocketDomain.AF_INET
        "INET6" -> SocketDomain.AF_INET6
        else -> { channel.closeNow(); throw UnsupportedOperationException("protocol family $protocolFamily") }
    }
    val fd = channel.execute(Submissions.socket(domain.posix, SocketType.withFlags(SocketType.SOCK_STREAM, SocketType.SOCK_NONBLOCK,
        SocketType.SOCK_CLOEXEC), SocketProtocol.IPPROTO_TCP.posix, 0))
    if (fd < 0) {
        channel.closeNow()
        throw IOException("socket: $fd")
    }
    return fd
}

/**
 * A listening socket on its own ring. Blocking mode waits for a connection on POLL_ADD; non-blocking mode keeps that
 * POLL_ADD in flight between calls and answers null until it fires, and [close] removes it before the CLOSE.
 */
internal class UringServerSocketChannel(
    private val file: File,
    private val channel: FunctionalUringFacade,
) : ServerSocketChannel(SelectorProvider.provider()) {
    private var nextToken: Long = 1
    private var open: Boolean = true
    private var blocking: Boolean = true
    /** The POLL_ADD watching for a connection, while one is in flight. */
    private var readiness: Long? = null
    private val lock = Any()

    /** [submission]'s result through this channel's ring. */
    private fun io(submission: UringSubmission): Int {
        if (!open) throw ClosedChannelException()
        return channel.execute(submission.copy(userData = nextToken++))
    }

    override fun begin() {}
    override fun end(completed: Boolean) {}

    override fun provider(): SelectorProvider = SelectorProvider.provider()
    override fun isRegistered(): Boolean = false
    override fun keyFor(sel: Selector): SelectionKey = throw IllegalStateException("not registered")
    override fun register(sel: Selector, ops: Int, att: Any): SelectionKey = throw UnsupportedOperationException("selector not supported — use uring")
    override fun register(sel: Selector, ops: Int): SelectionKey = register(sel, ops, Unit)
    override fun isBlocking(): Boolean = blocking
    override fun blockingLock(): Any = lock
    override fun configureBlocking(block: Boolean): SelectableChannel = also { blocking = block }

    override fun validOps(): Int = SelectionKey.OP_ACCEPT

    override fun bind(address: String): ServerSocketChannel = bind(address, 0)

    /** BIND then LISTEN; a [backlog] below 1 is the JDK's 50. */
    override fun bind(address: String, backlog: Int): ServerSocketChannel {
        val bound = io(Submissions.bind(file.id, sockaddr(address), 0))
        if (bound < 0) throw IOException("bind $address: $bound")
        val listening = io(Submissions.listen(file.id, if (backlog < 1) 50 else backlog, 0))
        if (listening < 0) throw IOException("listen $address: $listening")
        return this
    }

    /** SO_REUSEADDR, a boolean, through the SETSOCKOPT socket command. */
    override fun <T> setOption(option: String, value: T): ServerSocketChannel {
        if (option != "SO_REUSEADDR") throw UnsupportedOperationException("'$option' not supported")
        val flag = ByteBuffer(4).order(ByteOrder.nativeOrder()).putInt(0, if (value == true) 1 else 0).array()
        val set = io(Submissions.setsockopt(file.id, UringOp.SOL_SOCKET, UringOp.SO_REUSEADDR, flag, 0))
        if (set < 0) throw IOException("setsockopt $option: $set")
        return this
    }

    override fun <T> getOption(option: String): T = throw UnsupportedOperationException("options not supported")
    override fun supportedOptions(): Set<String> = setOf("SO_REUSEADDR")

    override fun accept(): SocketChannel? {
        while (true) {
            if (!open) throw ClosedChannelException()
            val watch = readiness ?: nextToken++.also {
                channel.enqueue(Submissions.pollAdd(file.id, UringOp.POLLIN, it))
                channel.submit()
                readiness = it
            }
            val ready = (if (blocking) channel.wait(1) else channel.peek()).singleOrNull() ?: return null
            check(ready.userData == watch) { "foreign completion ${ready.userData}" }
            readiness = null
            if (ready.res < 0) throw IOException("accept: ${ready.res}")
            val fd = io(Submissions.accept(file.id, SocketType.withFlags(SocketType.SOCK_NONBLOCK, SocketType.SOCK_CLOEXEC), 0))
            if (fd >= 0) return UringSocketChannel(File.fromFd(fd), UringChannels.open(4), connected = true)
            // Another acceptor took the connection: watch again.
            if (fd != -11) throw IOException("accept: $fd")
        }
    }

    /** The bound address, `a.b.c.d:port` or `[ipv6]:port`, from the GETSOCKNAME socket command. */
    override fun getLocalAddress(): String {
        val address = ByteBuffer(128)
        val named = io(Submissions.getsockname(file.id, address, false, 0))
        if (named < 0) throw IOException("getsockname: $named")
        return sockaddrText(address.array())
    }

    override fun close() {
        if (!open) return
        try {
            readiness?.let { watch ->
                // The watch settles -ECANCELED (or with its readiness, if it fired first); the removal 0 or -ENOENT.
                channel.enqueue(Submissions.pollRemove(watch, nextToken++))
                channel.submit()
                channel.wait(2)
                readiness = null
            }
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
