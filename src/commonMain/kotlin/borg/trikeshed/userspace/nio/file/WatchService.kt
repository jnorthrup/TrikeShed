@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.lib.Closeable

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface WatchService : Closeable {
    override fun close(): Unit
    fun poll(): WatchKey
    fun poll(p0: Long, p1: kotlin.time.DurationUnit): WatchKey
    fun take(): WatchKey
}
