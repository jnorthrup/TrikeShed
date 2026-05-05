@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.Closeable

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface WatchService : Closeable {
    fun close(): Unit
    fun poll(): borg.trikeshed.userspace.nio.file.WatchKey
    fun poll(p0: Long, p1: java.util.concurrent.TimeUnit): borg.trikeshed.userspace.nio.file.WatchKey
    fun take(): borg.trikeshed.userspace.nio.file.WatchKey
}
