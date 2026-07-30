@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousServerSocketChannel {
    protected constructor(provider: AsynchronousChannelProvider)
    abstract fun provider(): AsynchronousChannelProvider
    abstract fun bind(address: String): AsynchronousServerSocketChannel
    abstract fun bind(address: String, backlog: Int): AsynchronousServerSocketChannel
    abstract fun <T> setOption(option: String, value: T): AsynchronousServerSocketChannel
    abstract fun <A> accept(attachment: A, handler: CompletionHandler<AsynchronousSocketChannel, in A>): Unit
    abstract fun accept(): AsynchronousSocketChannel
    abstract fun getLocalAddress(): String

    companion object {
        fun `open`(group: AsynchronousChannelGroup): AsynchronousServerSocketChannel = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(group)
        fun `open`(): AsynchronousServerSocketChannel = throw UnsupportedOperationException("stub removed")
    }
}
