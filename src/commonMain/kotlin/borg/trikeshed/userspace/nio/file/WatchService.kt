@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.Closeable

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface WatchService : Closeable {
    override fun close(): Unit = TODO("NIO common stub")
    fun poll(): WatchKey = TODO("NIO common stub")
    fun poll(p0: Long, p1: kotlin.time.DurationUnit): WatchKey = TODO("NIO common stub")
    fun take(): WatchKey = TODO("NIO common stub")
}
