@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class ServerSocketChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel, borg.trikeshed.userspace.nio.channels.NetworkChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
    fun validOps(): Int = TODO("NIO common stub")
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.ServerSocketChannel = TODO("NIO common stub")
    fun bind(p0: java.net.SocketAddress, p1: Int): borg.trikeshed.userspace.nio.channels.ServerSocketChannel = TODO("NIO common stub")
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.ServerSocketChannel = TODO("NIO common stub")
    fun socket(): java.net.ServerSocket = TODO("NIO common stub")
    fun accept(): borg.trikeshed.userspace.nio.channels.SocketChannel = TODO("NIO common stub")
    fun getLocalAddress(): java.net.SocketAddress = TODO("NIO common stub")
    fun setOption(p0: java.net.SocketOption, p1: Any): borg.trikeshed.userspace.nio.channels.NetworkChannel = TODO("NIO common stub")
    companion object {
        fun `open`(): borg.trikeshed.userspace.nio.channels.ServerSocketChannel = TODO("NIO common stub")
        fun `open`(p0: java.net.ProtocolFamily): borg.trikeshed.userspace.nio.channels.ServerSocketChannel = TODO("NIO common stub")
    }
}
