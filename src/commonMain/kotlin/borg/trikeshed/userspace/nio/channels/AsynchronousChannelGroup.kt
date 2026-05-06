@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class AsynchronousChannelGroup {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider)
    fun provider(): borg.trikeshed.userspace.nio.channels.spi.AsynchronousChannelProvider = TODO("NIO common stub")
    fun isShutdown(): Boolean = TODO("NIO common stub")
    fun isTerminated(): Boolean = TODO("NIO common stub")
    fun shutdown(): Unit = TODO("NIO common stub")
    fun shutdownNow(): Unit = TODO("NIO common stub")
    fun awaitTermination(p0: Long, p1: java.util.concurrent.TimeUnit): Boolean = TODO("NIO common stub")
    companion object {
        fun withFixedThreadPool(p0: Int, p1: java.util.concurrent.ThreadFactory): borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup = TODO("NIO common stub")
        fun withCachedThreadPool(p0: java.util.concurrent.ExecutorService, p1: Int): borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup = TODO("NIO common stub")
        fun withThreadPool(p0: java.util.concurrent.ExecutorService): borg.trikeshed.userspace.nio.channels.AsynchronousChannelGroup = TODO("NIO common stub")
    }
}
