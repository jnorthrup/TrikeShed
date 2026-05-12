@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public open class FileLock {
    private val _channel: Any
    private val _position: Long
    private val _size: Long
    private val _shared: Boolean

    public constructor(channel: FileChannel, position: Long, size: Long, shared: Boolean) {
        _channel = channel; _position = position; _size = size; _shared = shared
    }
    public constructor(channel: AsynchronousFileChannel, position: Long, size: Long, shared: Boolean) {
        _channel = channel; _position = position; _size = size; _shared = shared
    }
    // TODO
    open fun channel(): FileChannel = _channel as FileChannel
    // TODO
    open fun acquiredBy(): Channel = _channel as Channel
    fun position(): Long = _position
    fun size(): Long = _size
    fun isShared(): Boolean = _shared
    fun overlaps(position: Long, size: Long): Boolean =
        position < (_position + _size) && (position + size) > _position
    // TODO
    open fun isValid(): Boolean = true
    // TODO
    open fun release() {}
    // TODO
    open fun close() { release() }
    override fun toString(): String= "FileLock($_channel, $_position, $_size, $_shared)"
}
