@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousChannelGroup {
    protected constructor(provider: AsynchronousChannelProvider)
    fun provider(): AsynchronousChannelProvider = TODO("NIO common stub")
    fun isShutdown(): Boolean = TODO("NIO common stub")
    fun isTerminated(): Boolean = TODO("NIO common stub")
    fun shutdown(): Unit = TODO("NIO common stub")
    fun shutdownNow(): Unit = TODO("NIO common stub")
    fun awaitTermination(timeout: Long): Boolean = TODO("NIO common stub")
    companion object {
        fun withFixedThreadPool(threadCount: Int): AsynchronousChannelGroup = TODO("NIO common stub")
        fun withCachedThreadPool(initialSize: Int): AsynchronousChannelGroup = TODO("NIO common stub")
        fun withThreadPool(): AsynchronousChannelGroup = TODO("NIO common stub")
    }
}
