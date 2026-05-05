@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileLock {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.FileChannel, p1: Long, p2: Long, p3: Boolean)
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel, p1: Long, p2: Long, p3: Boolean)
    fun channel(): borg.trikeshed.userspace.nio.channels.FileChannel
    fun acquiredBy(): borg.trikeshed.userspace.nio.channels.Channel
    fun position(): Long
    fun size(): Long
    fun isShared(): Boolean
    fun overlaps(p0: Long, p1: Long): Boolean
    fun isValid(): Boolean
    fun release(): Unit
    fun close(): Unit
    override fun toString(): String
}
