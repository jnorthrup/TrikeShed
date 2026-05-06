@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class SocketChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel, borg.trikeshed.userspace.nio.channels.ByteChannel, borg.trikeshed.userspace.nio.channels.ScatteringByteChannel, borg.trikeshed.userspace.nio.channels.GatheringByteChannel, borg.trikeshed.userspace.nio.channels.NetworkChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
    fun validOps(): Int = TODO("NIO common stub")
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
    fun shutdownInput(): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
    fun shutdownOutput(): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
    fun socket(): java.net.Socket = TODO("NIO common stub")
    fun isConnected(): Boolean = TODO("NIO common stub")
    fun isConnectionPending(): Boolean = TODO("NIO common stub")
    fun connect(p0: java.net.SocketAddress): Boolean = TODO("NIO common stub")
    fun finishConnect(): Boolean = TODO("NIO common stub")
    fun getRemoteAddress(): java.net.SocketAddress = TODO("NIO common stub")
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long = TODO("NIO common stub")
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long = TODO("NIO common stub")
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long = TODO("NIO common stub")
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long = TODO("NIO common stub")
    fun getLocalAddress(): java.net.SocketAddress = TODO("NIO common stub")
    fun setOption(p0: java.net.SocketOption, p1: Any): borg.trikeshed.userspace.nio.channels.NetworkChannel = TODO("NIO common stub")
    companion object {
        fun `open`(): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
        fun `open`(p0: java.net.ProtocolFamily): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
        fun `open`(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
    }
}
