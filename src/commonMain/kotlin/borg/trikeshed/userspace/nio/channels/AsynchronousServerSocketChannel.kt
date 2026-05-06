@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousServerSocketChannel {
    protected constructor(provider: AsynchronousChannelProvider)
    fun provider(): AsynchronousChannelProvider = TODO("NIO common stub")
    fun bind(address: String): AsynchronousServerSocketChannel = TODO("NIO common stub")
    fun bind(address: String, backlog: Int): AsynchronousServerSocketChannel = TODO("NIO common stub")
    fun <T> setOption(option: String, value: T): AsynchronousServerSocketChannel = TODO("NIO common stub")
    fun <A> accept(attachment: A, handler: CompletionHandler<AsynchronousSocketChannel, in A>): Unit = TODO("NIO common stub")
    fun accept(): AsynchronousSocketChannel = TODO("NIO common stub")
    fun getLocalAddress(): String = TODO("NIO common stub")

    companion object {
        fun `open`(group: AsynchronousChannelGroup): AsynchronousServerSocketChannel = TODO("NIO common stub")
        fun `open`(): AsynchronousServerSocketChannel = TODO("NIO common stub")
    }
}
