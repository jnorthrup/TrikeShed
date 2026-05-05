@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class AsynchronousServerSocketChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider)
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.AsynchronousServerSocketChannel
    fun bind(p0: java.net.SocketAddress, p1: Int): borg.trikeshed.userspace.nio.channels.AsynchronousServerSocketChannel
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.AsynchronousServerSocketChannel
    fun <A> accept(p0: A, p1: borg.trikeshed.userspace.nio.channels.CompletionHandler<borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel, in A>): Unit
    fun accept(): java.util.concurrent.Future<borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel>
    fun getLocalAddress(): java.net.SocketAddress
    fun setOption(p0: java.net.SocketOption, p1: Any): borg.trikeshed.userspace.nio.channels.NetworkChannel
    companion object {
        fun `open`(p0: borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup): borg.trikeshed.userspace.nio.channels.AsynchronousServerSocketChannel
        fun `open`(): borg.trikeshed.userspace.nio.channels.AsynchronousServerSocketChannel
    }
}
