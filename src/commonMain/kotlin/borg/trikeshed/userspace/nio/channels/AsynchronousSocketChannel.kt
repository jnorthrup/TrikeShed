@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class AsynchronousSocketChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider)
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel
    fun shutdownInput(): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel
    fun shutdownOutput(): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel
    fun getRemoteAddress(): java.net.SocketAddress
    fun <A> connect(p0: java.net.SocketAddress, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Void, in A>): Unit
    fun connect(p0: java.net.SocketAddress): java.util.concurrent.Future<java.lang.Void>
    fun <A> read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long, p2: java.util.concurrent.TimeUnit, p3: A, p4: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun <A> read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): java.util.concurrent.Future<java.lang.Integer>
    fun <A> read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int, p3: Long, p4: java.util.concurrent.TimeUnit, p5: A, p6: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Long, in A>): Unit
    fun <A> write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long, p2: java.util.concurrent.TimeUnit, p3: A, p4: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun <A> write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): java.util.concurrent.Future<java.lang.Integer>
    fun <A> write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int, p3: Long, p4: java.util.concurrent.TimeUnit, p5: A, p6: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Long, in A>): Unit
    fun getLocalAddress(): java.net.SocketAddress
    fun setOption(p0: java.net.SocketOption, p1: Any): borg.trikeshed.userspace.nio.channels.NetworkChannel
    companion object {
        fun `open`(p0: borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel
        fun `open`(): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel
    }
}
