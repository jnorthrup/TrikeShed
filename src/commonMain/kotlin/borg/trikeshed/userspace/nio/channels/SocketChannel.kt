@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channel.Channel
import borg.trikeshed.userspace.nio.channel.Channels
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider

public abstract class SocketChannel : AbstractSelectableChannel, ByteChannel, ScatteringByteChannel, GatheringByteChannel, NetworkChannel {
    constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): SocketChannel
    public abstract override fun <T> setOption(option: String, value: T): SocketChannel
    fun shutdownInput(): SocketChannel = TODO("shutdown")
    fun shutdownOutput(): SocketChannel = TODO("shutdown")
    fun isConnected(): Boolean = false
    fun isConnectionPending(): Boolean = false
    fun connect(address: String): Boolean = TODO("connect")
    fun finishConnect(): Boolean = false
    fun getRemoteAddress(): String = "0.0.0.0:0"
    public abstract override fun read(dst: ByteBuffer): Int
    public abstract override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteBuffer>): Long
    public abstract override fun write(src: ByteBuffer): Int
    public abstract override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteBuffer>): Long
    public abstract override fun getLocalAddress(): String

    companion object {
        fun open(): SocketChannel {
            val file = Channels.socket(SocketDomain.AF_INET.posix, SocketType.SOCK_STREAM.mask, SocketProtocol.IPPROTO_TCP.posix)
            val channel = Channels.open()
            return UringSocketChannel(file, channel)
        }
        fun openWithProtocolFamily(protocolFamily: String): SocketChannel = open()
        fun openWithRemote(address: String): SocketChannel { val ch = open(); ch.connect(address); return ch }
    }
}

internal class UringSocketChannel(
    private val file: File,
    private val channel: Channel,
) : SocketChannel(SelectorProvider.provider()) {
    private var nextToken: Long = 1
    private var open: Boolean = true
    private val lock = Any()

    override fun begin() {}
    override fun end(completed: Boolean) {}
    override fun provider(): SelectorProvider = SelectorProvider.provider()
    override fun isRegistered(): Boolean = false
    override fun keyFor(sel: Selector): SelectionKey = throw IllegalStateException("not registered")
    override fun register(sel: Selector, ops: Int, att: Any): SelectionKey = throw UnsupportedOperationException("selector not supported")
    override fun register(sel: Selector, ops: Int): SelectionKey = register(sel, ops, Unit)
    override fun isBlocking(): Boolean = true
    override fun blockingLock(): Any = lock
    override fun configureBlocking(block: Boolean): SelectableChannel = this
    override fun validOps(): Int = SelectionKey.OP_READ or SelectionKey.OP_WRITE or SelectionKey.OP_CONNECT
    override fun bind(address: String): SocketChannel = this
    override fun <T> setOption(option: String, value: T): SocketChannel = this

    override fun read(dst: ByteBuffer): Int {
        val token = nextToken++
        channel.read(file, dst, 0L, token)
        channel.submit()
        return channel.wait(1).firstOrNull()?.res ?: -1
    }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(dsts.size)) { val n = read(dsts[i]); if (n < 0) return if (total == 0L) -1 else total; total += n }
        return total
    }

    override fun read(dsts: Array<out ByteBuffer>): Long = read(dsts, 0, dsts.size)

    override fun write(src: ByteBuffer): Int {
        val token = nextToken++
        channel.write(file, src, 0L, token)
        channel.submit()
        return channel.wait(1).firstOrNull()?.res ?: -1
    }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(srcs.size)) { val n = write(srcs[i]); if (n < 0) return if (total == 0L) -1 else total; total += n }
        return total
    }

    override fun write(srcs: Array<out ByteBuffer>): Long = write(srcs, 0, srcs.size)
    override fun getLocalAddress(): String = "0.0.0.0:0"
    override fun close() { open = false }
    override fun isOpen(): Boolean = open
    override fun implCloseSelectableChannel() { open = false }
    override fun implConfigureBlocking(block: Boolean) {}
}
