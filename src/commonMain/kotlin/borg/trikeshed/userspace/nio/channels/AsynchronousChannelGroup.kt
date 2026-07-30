@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousChannelGroup {
    protected constructor(provider: AsynchronousChannelProvider)
    abstract fun provider(): AsynchronousChannelProvider
    abstract fun isShutdown(): Boolean
    abstract fun isTerminated(): Boolean
    abstract fun shutdown(): Unit
    abstract fun shutdownNow(): Unit
    abstract fun awaitTermination(timeout: Long): Boolean
    companion object {
        fun withFixedThreadPool(threadCount: Int): AsynchronousChannelGroup = AsynchronousChannelProvider.provider().openAsynchronousChannelGroupWithThreadCount(threadCount)
        fun withCachedThreadPool(initialSize: Int): AsynchronousChannelGroup = AsynchronousChannelProvider.provider().openAsynchronousChannelGroupWithInitialSize(initialSize)
        fun withThreadPool(): AsynchronousChannelGroup = throw UnsupportedOperationException("stub removed")
    }
}
