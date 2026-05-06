@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class FileChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractInterruptibleChannel, borg.trikeshed.userspace.nio.channels.SeekableByteChannel, borg.trikeshed.userspace.nio.channels.GatheringByteChannel, borg.trikeshed.userspace.nio.channels.ScatteringByteChannel {
    protected constructor()
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long = TODO("NIO common stub")
    fun read(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long = TODO("NIO common stub")
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer): Int = TODO("NIO common stub")
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long = TODO("NIO common stub")
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long = TODO("NIO common stub")
    fun position(): Long = TODO("NIO common stub")
    fun position(p0: Long): borg.trikeshed.userspace.nio.channels.FileChannel = TODO("NIO common stub")
    fun size(): Long = TODO("NIO common stub")
    fun truncate(p0: Long): borg.trikeshed.userspace.nio.channels.FileChannel = TODO("NIO common stub")
    fun force(p0: Boolean): Unit = TODO("NIO common stub")
    fun transferTo(p0: Long, p1: Long, p2: borg.trikeshed.userspace.nio.channels.WritableByteChannel): Long = TODO("NIO common stub")
    fun transferFrom(p0: borg.trikeshed.userspace.nio.channels.ReadableByteChannel, p1: Long, p2: Long): Long = TODO("NIO common stub")
    fun read(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long): Int = TODO("NIO common stub")
    fun write(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: Long): Int = TODO("NIO common stub")
    fun map(p0: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode, p1: Long, p2: Long): borg.trikeshed.userspace.nio.ByteBuffer = TODO("NIO common stub")
    fun map(p0: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode, p1: Long, p2: Long, p3: java.lang.foreign.Arena): java.lang.foreign.MemorySegment = TODO("NIO common stub")
    fun lock(p0: Long, p1: Long, p2: Boolean): borg.trikeshed.userspace.nio.channels.FileLock = TODO("NIO common stub")
    fun lock(): borg.trikeshed.userspace.nio.channels.FileLock = TODO("NIO common stub")
    fun tryLock(p0: Long, p1: Long, p2: Boolean): borg.trikeshed.userspace.nio.channels.FileLock = TODO("NIO common stub")
    fun tryLock(): borg.trikeshed.userspace.nio.channels.FileLock = TODO("NIO common stub")
    companion object {
        fun `open`(p0: borg.trikeshed.userspace.nio.file.Path, p1: java.util.Set<out borg.trikeshed.userspace.nio.file.OpenOption>, vararg p2: borg.trikeshed.userspace.nio.file.attribute.FileAttribute<*>): borg.trikeshed.userspace.nio.channels.FileChannel = TODO("NIO common stub")
        fun `open`(p0: borg.trikeshed.userspace.nio.file.Path, vararg p1: borg.trikeshed.userspace.nio.file.OpenOption): borg.trikeshed.userspace.nio.channels.FileChannel = TODO("NIO common stub")
    }

    public open class MapMode {
        override fun toString(): String = TODO("NIO common stub")
        companion object {
            val READ_ONLY: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode = TODO("NIO common stub")
            val READ_WRITE: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode = TODO("NIO common stub")
            val PRIVATE: borg.trikeshed.userspace.nio.channels.FileChannel.MapMode = TODO("NIO common stub")
        }
    }
}
