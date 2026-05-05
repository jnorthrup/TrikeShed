@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class SocketChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel, borg.trikeshed.userspace.nio.channels.ByteChannel, borg.trikeshed.userspace.nio.channels.ScatteringByteChannel, borg.trikeshed.userspace.nio.channels.GatheringByteChannel, borg.trikeshed.userspace.nio.channels.NetworkChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
    fun validOps(): Int
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.SocketChannel
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.SocketChannel
    fun shutdownInput(): borg.trikeshed.userspace.nio.channels.SocketChannel
    fun shutdownOutput(): borg.trikeshed.userspace.nio.channels.SocketChannel
    fun socket(): java.net.Socket
    fun isConnected(): Boolean
    fun isConnectionPending(): Boolean
    fun connect(p0: java.net.SocketAddress): Boolean
    fun finishConnect(): Boolean
    fun getRemoteAddress(): java.net.SocketAddress
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long
    fun getLocalAddress(): java.net.SocketAddress
    fun setOption(p0: java.net.SocketOption, p1: Any): borg.trikeshed.userspace.nio.channels.NetworkChannel
    companion object {
        fun `open`(): borg.trikeshed.userspace.nio.channels.SocketChannel
        fun `open`(p0: java.net.ProtocolFamily): borg.trikeshed.userspace.nio.channels.SocketChannel
        fun `open`(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.SocketChannel
    }
}
