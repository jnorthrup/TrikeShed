@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.Channel
import borg.trikeshed.userspace.Channels
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
import borg.trikeshed.userspace.nio.file.File

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class DatagramChannel : AbstractSelectableChannel, ByteChannel, ScatteringByteChannel, GatheringByteChannel, MulticastChannel {
    protected constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun close()
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): DatagramChannel
    public abstract override fun <T> setOption(option: String, value: T): DatagramChannel
    public abstract fun isConnected(): Boolean
    public abstract fun connect(address: String): DatagramChannel
    public abstract fun disconnect(): DatagramChannel
    public abstract fun getRemoteAddress(): String
    public abstract fun receive(dst: ByteRegion): String
    public abstract fun send(src: ByteBuffer, address: String): Int
    public abstract override fun read(dst: ByteBuffer): Int
    public abstract override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteBuffer>): Long
    public abstract override fun write(src: ByteBuffer): Int
    public abstract override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteBuffer>): Long
    public abstract override fun getLocalAddress(): String

    companion object {
        fun `open`(): DatagramChannel {
            val file = Channels.socket(SocketDomain.AF_INET.posix, SocketType.SOCK_DGRAM.mask, SocketProtocol.IPPROTO_UDP.posix)
            val channel = Channels.open()
            return UringDatagramChannel(file, channel)
        }
        fun `open`(protocolFamily: String): DatagramChannel = `open`()
    }
}

internal class UringDatagramChannel(
    private val file: File,
    private val channel: Channel,
) : DatagramChannel(SelectorProvider.provider()) {
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

    override fun validOps(): Int = 1 or 4 // SelectionKey.OP_READ or OP_WRITE (hardcoded to avoid SelectionKey changes)

    override fun bind(address: String): DatagramChannel = this
    override fun <T> setOption(option: String, value: T): DatagramChannel = this
    override fun <T> getOption(option: String): T = throw UnsupportedOperationException("options not supported")
    override fun supportedOptions(): Set<String> = emptySet()

    override fun isConnected(): Boolean = false
    override fun connect(address: String): DatagramChannel = this
    override fun disconnect(): DatagramChannel = this
    override fun getRemoteAddress(): String = "0.0.0.0:0"

    override fun receive(dst: ByteRegion): String = ""
    override fun send(src: ByteBuffer, address: String): Int = 0

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

    override fun join(group: String, networkInterface: String): MembershipKey = throw UnsupportedOperationException()
    override fun join(group: String, networkInterface: String, source: String): MembershipKey = throw UnsupportedOperationException()
}
