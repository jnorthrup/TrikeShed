package borg.trikeshed

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.ByteRegion
import java.io.RandomAccessFile
import java.nio.ByteBuffer as JavaByteBuffer

/**
 * JVM FileChannel-based SeekHandle.
 *
 * Uses FileChannel for portable NIO access across all JDKs.
 * For high-performance batching, use IoUringSeekHandle on Linux via JNI/JEP 436.
 */
class FileChannelSeekHandle : SeekHandle {
   val channels = mutableMapOf<Long, RandomAccessFile>()
   var nextId: Long = 1

    override fun open(filename: String, readOnly: Boolean): Long {
        val mode = if (readOnly) "r" else "rw"
        val raf = RandomAccessFile(filename, mode)
        val id = nextId++
        channels[id] = raf
        return id
    }

    override fun close(handle: Long) {
        channels.remove(handle)?.close()
    }

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val raf = channels[handle] ?: return -1
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        raf.seek(fileOffset)
        return raf.read(backing, offset, dst.size)
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int {
        val raf = channels[handle] ?: return -1
        val bytes = src.toArray()
        raf.seek(fileOffset)
        raf.write(bytes, 0, bytes.size)
        return bytes.size
    }

    override fun size(handle: Long): Long {
        return channels[handle]?.length() ?: -1
    }

    override fun read(handle: Long, dst: ByteRegion): Int {
        val raf = channels[handle] ?: return -1
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        return raf.read(backing, offset, dst.size)
    }

    override fun write(handle: Long, src: ByteSeries): Int {
        val raf = channels[handle] ?: return -1
        val bytes = src.toArray()
        raf.write(bytes, 0, bytes.size)
        return bytes.size
    }

    override fun seek(handle: Long, position: Long): Long {
        val raf = channels[handle] ?: return -1
        raf.seek(position)
        return raf.filePointer
    }
}

/**
 * Alternative NIO FileChannel implementation with direct ByteBuffer support.
 * Avoids RandomAccessFile overhead for bulk operations.
 */
class NioSeekHandle : SeekHandle {
   val channels = mutableMapOf<Long, java.nio.channels.FileChannel>()
   var nextId: Long = 1

    override fun open(filename: String, readOnly: Boolean): Long {
        val path = java.nio.file.Paths.get(filename)
        val channel = java.nio.channels.FileChannel.open(
            path,
            if (readOnly) {
                setOf(java.nio.file.StandardOpenOption.READ)
            } else {
                setOf(
                    java.nio.file.StandardOpenOption.READ,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.CREATE,
                )
            }
        )
        val id = nextId++
        channels[id] = channel
        return id
    }

    override fun close(handle: Long) {
        channels.remove(handle)?.close()
    }

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val channel = channels[handle] ?: return -1
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        val buffer = JavaByteBuffer.wrap(backing, offset, dst.size)
        return channel.read(buffer, fileOffset)
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int {
        val channel = channels[handle] ?: return -1
        val bytes = src.toArray()
        val buffer = JavaByteBuffer.wrap(bytes)
        return channel.write(buffer, fileOffset)
    }

    override fun size(handle: Long): Long {
        return channels[handle]?.size() ?: -1
    }

    override fun read(handle: Long, dst: ByteRegion): Int {
        val channel = channels[handle] ?: return -1
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        val buffer = JavaByteBuffer.wrap(backing, offset, dst.size)
        return channel.read(buffer)
    }

    override fun write(handle: Long, src: ByteSeries): Int {
        val channel = channels[handle] ?: return -1
        val buffer = JavaByteBuffer.wrap(src.toArray())
        return channel.write(buffer)
    }

    override fun seek(handle: Long, position: Long): Long {
        val channel = channels[handle] ?: return -1
        channel.position(position)
        return channel.position()
    }
}

/** JVM actual: returns NIO FileChannel implementation by default. */
actual fun platformSeekHandle(): SeekHandle = NioSeekHandle()

/** io_uring not available on JVM without JNI or JEP 436 preview. Returns null to use fallback. */
actual fun ioUringHandle(): SeekHandle? = null
