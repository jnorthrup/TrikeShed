@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class ServerSocketChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel, borg.trikeshed.userspace.nio.channels.NetworkChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
    fun validOps(): Int
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.ServerSocketChannel
    fun bind(p0: java.net.SocketAddress, p1: Int): borg.trikeshed.userspace.nio.channels.ServerSocketChannel
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.ServerSocketChannel
    fun socket(): java.net.ServerSocket
    fun accept(): borg.trikeshed.userspace.nio.channels.SocketChannel
    fun getLocalAddress(): java.net.SocketAddress
    fun setOption(p0: java.net.SocketOption, p1: Any): borg.trikeshed.userspace.nio.channels.NetworkChannel
    companion object {
        fun `open`(): borg.trikeshed.userspace.nio.channels.ServerSocketChannel
        fun `open`(p0: java.net.ProtocolFamily): borg.trikeshed.userspace.nio.channels.ServerSocketChannel
    }
}
