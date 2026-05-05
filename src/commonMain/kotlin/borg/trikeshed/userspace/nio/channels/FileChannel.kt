@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class FileChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractInterruptibleChannel, borg.trikeshed.userspace.nio.channels.SeekableByteChannel, borg.trikeshed.userspace.nio.channels.GatheringByteChannel, borg.trikeshed.userspace.nio.channels.ScatteringByteChannel {
    protected constructor()
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long
    fun position(): Long
    fun position(p0: Long): borg.trikeshed.userspace.nio.channels.FileChannel
    fun size(): Long
    fun truncate(p0: Long): borg.trikeshed.userspace.nio.channels.FileChannel
    fun force(p0: Boolean): Unit
    fun transferTo(p0: Long, p1: Long, p2: borg.trikeshed.userspace.nio.channels.WritableByteChannel): Long
    fun transferFrom(p0: borg.trikeshed.userspace.nio.channels.ReadableByteChannel, p1: Long, p2: Long): Long
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long): Int
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long): Int
    fun map(p0: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode, p1: Long, p2: Long): borg.trikeshed.userspace.nio.MappedByteBuffer
    fun map(p0: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode, p1: Long, p2: Long, p3: java.lang.foreign.Arena): java.lang.foreign.MemorySegment
    fun lock(p0: Long, p1: Long, p2: Boolean): borg.trikeshed.userspace.nio.channels.FileLock
    fun lock(): borg.trikeshed.userspace.nio.channels.FileLock
    fun tryLock(p0: Long, p1: Long, p2: Boolean): borg.trikeshed.userspace.nio.channels.FileLock
    fun tryLock(): borg.trikeshed.userspace.nio.channels.FileLock
    companion object {
        fun `open`(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.FileChannel
        fun `open`(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.channels.FileChannel
    }

    expect open class MapMode {
        override fun toString(): String
        companion object {
            val READ_ONLY: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode
            val READ_WRITE: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode
            val PRIVATE: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode
        }
    }
}
