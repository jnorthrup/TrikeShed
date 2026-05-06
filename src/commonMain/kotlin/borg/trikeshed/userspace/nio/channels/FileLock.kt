@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileLock {
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.FileChannel, p1: Long, p2: Long, p3: Boolean)
    protected constructor(p0: borg.trikeshed.userspace.nio.channels.AsynchronousFileChannel, p1: Long, p2: Long, p3: Boolean)
    fun channel(): borg.trikeshed.userspace.nio.channels.FileChannel = TODO("NIO common stub")
    fun acquiredBy(): borg.trikeshed.userspace.nio.channels.Channel = TODO("NIO common stub")
    fun position(): Long = TODO("NIO common stub")
    fun size(): Long = TODO("NIO common stub")
    fun isShared(): Boolean = TODO("NIO common stub")
    fun overlaps(p0: Long, p1: Long): Boolean = TODO("NIO common stub")
    fun isValid(): Boolean = TODO("NIO common stub")
    fun release(): Unit = TODO("NIO common stub")
    fun close(): Unit = TODO("NIO common stub")
    override fun toString(): String = TODO("NIO common stub")
}
