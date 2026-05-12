@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousSocketChannel {
    protected constructor(provider: AsynchronousChannelProvider)
    // TODO
    abstract fun provider(): AsynchronousChannelProvider
    // TODO
    abstract fun bind(address: String): AsynchronousSocketChannel
    // TODO
    abstract fun <T> setOption(option: String, value: T): AsynchronousSocketChannel
    // TODO
    abstract fun shutdownInput(): AsynchronousSocketChannel
    // TODO
    abstract fun shutdownOutput(): AsynchronousSocketChannel
    // TODO
    abstract fun getRemoteAddress():CharSequence// TODO
    abstract fun <A> connect(address: String, attachment: A, handler: CompletionHandler<Unit?, in A>): Unit
    // TODO
    abstract fun connect(address: String): Unit?
    // TODO
    abstract fun <A> read(dst: ByteBuffer, timeout: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    // TODO
    abstract fun <A> read(dst: ByteBuffer, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    // TODO
    abstract fun read(dst: ByteBuffer): Int
    // TODO
    abstract fun <A> read(dsts: Array<out ByteBuffer>, offset: Int, length: Int, timeout: Long, attachment: A, handler: CompletionHandler<Long, in A>): Unit
    // TODO
    abstract fun <A> write(src: ByteSeries, timeout: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    // TODO
    abstract fun <A> write(src: ByteSeries, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    // TODO
    abstract fun write(src: ByteSeries): Int
    // TODO
    abstract fun <A> write(srcs: Array<out ByteSeries>, offset: Int, length: Int, timeout: Long, attachment: A, handler: CompletionHandler<Long, in A>): Unit
    // TODO
    abstract fun getLocalAddress():CharSequencecompanion object {
        fun `open`(group: AsynchronousChannelGroup): AsynchronousSocketChannel = TODO("NIO common stub")
        fun `open`(): AsynchronousSocketChannel = TODO("NIO common stub")
    }
}
