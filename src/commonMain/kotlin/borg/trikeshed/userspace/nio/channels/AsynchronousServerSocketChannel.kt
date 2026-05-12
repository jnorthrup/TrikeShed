@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousServerSocketChannel {
    protected constructor(provider: AsynchronousChannelProvider)
    // TODO
    abstract fun provider(): AsynchronousChannelProvider
    // TODO
    abstract fun bind(address: CharSequence): AsynchronousServerSocketChannel
    // TODO
    abstract fun bind(address: CharSequence, backlog: Int): AsynchronousServerSocketChannel
    // TODO
    abstract fun <T> setOption(option: CharSequence, value: T): AsynchronousServerSocketChannel
    // TODO
    abstract fun <A> accept(attachment: A, handler: CompletionHandler<AsynchronousSocketChannel, in A>): Unit
    // TODO
    abstract fun accept(): AsynchronousSocketChannel
    // TODO
    abstract fun getLocalAddress(): CharSequence
    companion object {
        fun `open`(group: AsynchronousChannelGroup): AsynchronousServerSocketChannel = TODO("NIO common stub")
        fun `open`(): AsynchronousServerSocketChannel = TODO("NIO common stub")
    }
}
