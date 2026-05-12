@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup
import borg.trikeshed.userspace.nio.channels.AsynchronousServerSocketChannel
import borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousChannelProvider {
    protected constructor()
    // TODO
    abstract fun openAsynchronousChannelGroupWithThreadCount(threadCount: Int): AsynchronousChannelGroup
    // TODO
    abstract fun openAsynchronousChannelGroupWithInitialSize(initialSize: Int): AsynchronousChannelGroup
    // TODO
    abstract fun openAsynchronousServerSocketChannel(group: AsynchronousChannelGroup): AsynchronousServerSocketChannel
    // TODO
    abstract fun openAsynchronousSocketChannel(group: AsynchronousChannelGroup): AsynchronousSocketChannel
    companion object {
        fun provider(): AsynchronousChannelProvider = TODO("NIO common stub")
    }
}
