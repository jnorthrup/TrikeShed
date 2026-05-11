@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class FileLock {
    public constructor(channel: FileChannel, position: Long, size: Long, shared: Boolean)
    public constructor(channel: AsynchronousFileChannel, position: Long, size: Long, shared: Boolean)
    fun channel(): FileChannel = TODO("NIO common stub")
    fun acquiredBy(): Channel = TODO("NIO common stub")
    fun position(): Long = TODO("NIO common stub")
    fun size(): Long = TODO("NIO common stub")
    fun isShared(): Boolean = TODO("NIO common stub")
    fun overlaps(position: Long, size: Long): Boolean = TODO("NIO common stub")
    fun isValid(): Boolean = TODO("NIO common stub")
    fun release(): Unit = TODO("NIO common stub")
    fun close(): Unit = TODO("NIO common stub")
    override fun toString(): String = TODO("NIO common stub")
}
