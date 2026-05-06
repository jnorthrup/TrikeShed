@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousSocketChannel {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider)
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider = TODO("NIO common stub")
    fun bind(p0: java.net.SocketAddress): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel = TODO("NIO common stub")
    fun <T> setOption(p0: java.net.SocketOption<T>, p1: T): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel = TODO("NIO common stub")
    fun shutdownInput(): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel = TODO("NIO common stub")
    fun shutdownOutput(): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel = TODO("NIO common stub")
    fun getRemoteAddress(): java.net.SocketAddress = TODO("NIO common stub")
    fun <A> connect(p0: java.net.SocketAddress, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Void, in A>): Unit = TODO("NIO common stub")
    fun connect(p0: java.net.SocketAddress): java.util.concurrent.Future<java.lang.Void> = TODO("NIO common stub")
    fun <A> read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long, p2: java.util.concurrent.TimeUnit, p3: A, p4: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit = TODO("NIO common stub")
    fun <A> read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit = TODO("NIO common stub")
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): java.util.concurrent.Future<java.lang.Integer> = TODO("NIO common stub")
    fun <A> read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int, p3: Long, p4: java.util.concurrent.TimeUnit, p5: A, p6: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Long, in A>): Unit = TODO("NIO common stub")
    fun <A> write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long, p2: java.util.concurrent.TimeUnit, p3: A, p4: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit = TODO("NIO common stub")
    fun <A> write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: A, p2: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Integer, in A>): Unit = TODO("NIO common stub")
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): java.util.concurrent.Future<java.lang.Integer> = TODO("NIO common stub")
    fun <A> write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int, p3: Long, p4: java.util.concurrent.TimeUnit, p5: A, p6: borg.trikeshed.userspace.nio.channels.CompletionHandler<java.lang.Long, in A>): Unit = TODO("NIO common stub")
    fun getLocalAddress(): java.net.SocketAddress = TODO("NIO common stub")
    fun setOption(p0: java.net.SocketOption, p1: Any): borg.trikeshed.userspace.nio.channels.NetworkChannel = TODO("NIO common stub")
    companion object {
        fun `open`(p0: borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel = TODO("NIO common stub")
        fun `open`(): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel = TODO("NIO common stub")
    }
}
