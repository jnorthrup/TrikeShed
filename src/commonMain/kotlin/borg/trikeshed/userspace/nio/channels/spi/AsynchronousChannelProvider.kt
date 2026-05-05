@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels.spi

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class AsynchronousChannelProvider {
    protected constructor()
    fun openAsynchronousChannelGroup(p0: Int, p1: java.util.concurrent.ThreadFactory): borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup
    fun openAsynchronousChannelGroup(p0: java.util.concurrent.ExecutorService, p1: Int): borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup
    fun openAsynchronousServerSocketChannel(p0: borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup): borg.trikeshed.userspace.nio.channels.AsynchronousServerSocketChannel
    fun openAsynchronousSocketChannel(p0: borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup): borg.trikeshed.userspace.nio.channels.AsynchronousSocketChannel
    companion object {
        fun provider(): borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
    }
}
