@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.Channel
import borg.trikeshed.userspace.Channels
import borg.trikeshed.userspace.File
import borg.trikeshed.userspace.Files
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
    fun force(metaData: Boolean): Unit = TODO("force via fdatasync/fsync")
    fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = TODO("sendfile/splice")
    fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = TODO("sendfile/splice")
    fun read(dst: ByteBuffer, position: Long): Int = TODO("pread")
    fun write(src: ByteBuffer, position: Long): Int = TODO("pwrite")
    fun map(mode: FileChannel.MapMode, position: Long, size: Long): ByteBuffer = TODO("mmap")
    fun lock(position: Long, size: Long, shared: Boolean): FileLock = TODO("fcntl lock")
    fun lock(): FileLock = TODO("fcntl lock")
    fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = null
    fun tryLock(): FileLock? = null

    companion object {
        fun open(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): FileChannel {
            val readOnly = !options.any { it is StandardOpenOption && it == StandardOpenOption.WRITE }
            val file = Files.open(path.toString(), readOnly)
            val channel = Channels.open()
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

internal class UringFileChannel(
    private val file: File,
    private val channel: Channel,
) : FileChannel() {
    private var pos: Long = 0
    private var nextToken: Long = 1
    private var open: Boolean = true

    override fun implCloseChannel() {}
    override fun begin() {}
    override fun end(completed: Boolean) {}

    override fun close() {
        if (!open) return
        channel.close(file, nextToken++)
        channel.submit()
        open = false
    }

    override fun isOpen(): Boolean = open

    override fun read(dst: ByteBuffer): Int {
        val token = nextToken++
        channel.read(file, dst, pos, token)
        channel.submit()
        val completed = channel.wait(1)
        val bytesRead = completed.firstOrNull()?.res ?: -1
        if (bytesRead > 0) pos += bytesRead
        return bytesRead
    }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(dsts.size)) {
            val n = read(dsts[i])
            if (n < 0) return if (total == 0L) -1 else total
            total += n
        }
        return total
    }

    override fun read(dsts: Array<out ByteBuffer>): Long = read(dsts, 0, dsts.size)

    override fun write(src: ByteBuffer): Int {
        val token = nextToken++
        channel.write(file, src, pos, token)
        channel.submit()
        val completed = channel.wait(1)
        val bytesWritten = completed.firstOrNull()?.res ?: -1
        if (bytesWritten > 0) pos += bytesWritten
        return bytesWritten
    }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var total: Long = 0
        for (i in offset until (offset + length).coerceAtMost(srcs.size)) {
            val n = write(srcs[i])
            if (n < 0) return if (total == 0L) -1 else total
            total += n
        }
        return total
    }

    override fun write(srcs: Array<out ByteBuffer>): Long = write(srcs, 0, srcs.size)

    override fun position(): Long = pos
    override fun position(newPosition: Long): FileChannel { pos = newPosition; return this }
    override fun size(): Long = TODO("fstat")
    override fun truncate(size: Long): FileChannel = TODO("ftruncate")
}
