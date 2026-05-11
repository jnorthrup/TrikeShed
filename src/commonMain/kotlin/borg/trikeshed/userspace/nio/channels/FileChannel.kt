@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.file.Path
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.attribute.FileAttribute
import borg.trikeshed.userspace.nio.channels.spi.AbstractInterruptibleChannel

/**
 * FileChannel wired behind UringFacade.
 *
 * Every read/write routes through Channel → ChannelImpl → FunctionalUringFacade.
 */
public abstract class FileChannel protected constructor() : AbstractInterruptibleChannel(), SeekableByteChannel, GatheringByteChannel, ScatteringByteChannel {
    public abstract override fun close()
    public abstract override fun isOpen(): Boolean
    public abstract override fun read(dst: ByteBuffer): Int
    public abstract override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun read(dsts: Array<out ByteBuffer>): Long
    public abstract override fun write(src: ByteBuffer): Int
    public abstract override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long
    public abstract override fun write(srcs: Array<out ByteBuffer>): Long
    public abstract override fun position(): Long
    public abstract override fun position(newPosition: Long): FileChannel
    public abstract override fun size(): Long
    public abstract override fun truncate(size: Long): FileChannel
    public abstract fun force(metaData: Boolean)
    public abstract fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long
    public abstract fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long
    public abstract fun read(dst: ByteBuffer, position: Long): Int
    public abstract fun write(src: ByteBuffer, position: Long): Int
    public abstract fun map(mode: MapMode, position: Long, size: Long): ByteBuffer
    public abstract fun lock(position: Long, size: Long, shared: Boolean): FileLock
    public abstract fun lock(): FileLock
    public abstract fun tryLock(position: Long, size: Long, shared: Boolean): FileLock?
    public abstract fun tryLock(): FileLock?

    companion object {
        fun open(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): FileChannel {
            val readOnly = !options.any { it is StandardOpenOption && it == StandardOpenOption.WRITE }
            val file = borg.trikeshed.userspace.Files.open(path.toString(), readOnly)
            val channel = borg.trikeshed.userspace.Channels.open()
            return UringFileChannel(file, channel)
        }
        fun open(path: Path, vararg options: OpenOption): FileChannel = open(path, options.toSet())
    }

    public open class MapMode {
        override fun toString(): String = "MapMode"
        companion object {
            val READ_ONLY: FileChannel.MapMode = object : MapMode() {}
            val READ_WRITE: FileChannel.MapMode = object : MapMode() {}
            val PRIVATE: FileChannel.MapMode = object : MapMode() {}
        }
    }
}