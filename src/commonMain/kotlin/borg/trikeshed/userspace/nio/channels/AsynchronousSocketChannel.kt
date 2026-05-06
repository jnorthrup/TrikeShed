@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousSocketChannel {
    protected constructor(provider: AsynchronousChannelProvider)
    fun provider(): AsynchronousChannelProvider = TODO("NIO common stub")
    fun bind(address: String): AsynchronousSocketChannel = TODO("NIO common stub")
    fun <T> setOption(option: String, value: T): AsynchronousSocketChannel = TODO("NIO common stub")
    fun shutdownInput(): AsynchronousSocketChannel = TODO("NIO common stub")
    fun shutdownOutput(): AsynchronousSocketChannel = TODO("NIO common stub")
    fun getRemoteAddress(): String = TODO("NIO common stub")
    fun <A> connect(address: String, attachment: A, handler: CompletionHandler<Unit?, in A>): Unit = TODO("NIO common stub")
    fun connect(address: String): Unit? = TODO("NIO common stub")
    fun <A> read(dst: ByteRegion, timeout: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit = TODO("NIO common stub")
    fun <A> read(dst: ByteRegion, attachment: A, handler: CompletionHandler<Int, in A>): Unit = TODO("NIO common stub")
    fun read(dst: ByteRegion): Int = TODO("NIO common stub")
    fun <A> read(dsts: Array<out ByteRegion>, offset: Int, length: Int, timeout: Long, attachment: A, handler: CompletionHandler<Long, in A>): Unit = TODO("NIO common stub")
    fun <A> write(src: ByteSeries, timeout: Long, attachment: A, handler: CompletionHandler<Int, in A>): Unit = TODO("NIO common stub")
    fun <A> write(src: ByteSeries, attachment: A, handler: CompletionHandler<Int, in A>): Unit = TODO("NIO common stub")
    fun write(src: ByteSeries): Int = TODO("NIO common stub")
    fun <A> write(srcs: Array<out ByteSeries>, offset: Int, length: Int, timeout: Long, attachment: A, handler: CompletionHandler<Long, in A>): Unit = TODO("NIO common stub")
    fun getLocalAddress(): String = TODO("NIO common stub")

    companion object {
        fun `open`(group: AsynchronousChannelGroup): AsynchronousSocketChannel = TODO("NIO common stub")
        fun `open`(): AsynchronousSocketChannel = TODO("NIO common stub")
    }
}
