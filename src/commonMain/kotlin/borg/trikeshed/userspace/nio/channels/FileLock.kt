@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileLock {
    private val channel: Channel
    private val position: Long
    private val size: Long
    private val shared: Boolean

    public constructor(channel: FileChannel, position: Long, size: Long, shared: Boolean) {
        this.channel = channel
        this.position = position
        this.size = size
        this.shared = shared
    }
    public constructor(channel: AsynchronousFileChannel, position: Long, size: Long, shared: Boolean) {
        this.channel = channel as Channel
        this.position = position
        this.size = size
        this.shared = shared
    }

    fun channel(): FileChannel? = channel as? FileChannel
    fun acquiredBy(): Channel = channel
    fun position(): Long = position
    fun size(): Long = size
    fun isShared(): Boolean = shared
    fun overlaps(position: Long, size: Long): Boolean {
        if (position + size <= this.position) return false
        if (this.position + this.size <= position) return false
        return true
    }
    abstract fun isValid(): Boolean
    abstract fun release(): Unit
    fun close(): Unit = release()
    override fun toString(): String = "${this::class.simpleName}[position=$position, size=$size, shared=$shared]"
}
