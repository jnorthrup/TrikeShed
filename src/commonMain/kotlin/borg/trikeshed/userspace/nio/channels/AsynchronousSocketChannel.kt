@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousSocketChannel {
    protected constructor(provider: AsynchronousChannelProvider)
    abstract fun provider(): AsynchronousChannelProvider
    abstract fun bind(address: String): AsynchronousSocketChannel
    abstract fun <T> setOption(option: String, value: T): AsynchronousSocketChannel
    abstract fun shutdownInput(): AsynchronousSocketChannel
    abstract fun shutdownOutput(): AsynchronousSocketChannel
    abstract fun getRemoteAddress(): String
    abstract fun <A> connect(address: String, attachment: A, handler: CompletionHandler<Unit?, in A>): Unit
    abstract fun connect(address: String): Unit?
    abstract fun <A> read(dst: ByteBuffer, timeout: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    abstract fun <A> read(dst: ByteBuffer, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    abstract fun read(dst: ByteBuffer): Int
    abstract fun <A> read(dsts: Array<out ByteBuffer>, offset: Int, length: Int, timeout: Long, attachment: A, handler: CompletionHandler<Long, in A>): Unit
    abstract fun <A> write(src: ByteSeries, timeout: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    abstract fun <A> write(src: ByteSeries, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    abstract fun write(src: ByteSeries): Int
    abstract fun <A> write(srcs: Array<out ByteSeries>, offset: Int, length: Int, timeout: Long, attachment: A, handler: CompletionHandler<Long, in A>): Unit
    abstract fun getLocalAddress(): String

    companion object {
        fun `open`(group: AsynchronousChannelGroup): AsynchronousSocketChannel = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(group)
        fun `open`(): AsynchronousSocketChannel = throw UnsupportedOperationException("stub removed")
    }
}
