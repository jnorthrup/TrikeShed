package borg.trikeshed.common

import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * JVM FileChannel-based SeekHandle.
 *
 * Uses FileChannel for portable NIO access across all JDKs.
 * For high-performance batching, use IoUringSeekHandle on Linux via JNI/JEP 436.
 */
class FileChannelSeekHandle : SeekHandle {
    private val channels = mutableMapOf<Long, RandomAccessFile>()
    private var nextId: Long = 1

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

    override fun pread(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
        val raf = channels[handle] ?: return -1
        raf.seek(fileOffset)
        return raf.read(buf, offset, length)
    }

    override fun size(handle: Long): Long {
        return channels[handle]?.length() ?: -1
    }

    override fun read(handle: Long, buf: ByteArray, offset: Int, length: Int): Int {
        val raf = channels[handle] ?: return -1
        return raf.read(buf, offset, length)
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
    private val channels = mutableMapOf<Long, java.nio.channels.FileChannel>()
    private var nextId: Long = 1

    override fun open(filename: String, readOnly: Boolean): Long {
        val path = java.nio.file.Paths.get(filename)
        val channel = java.nio.channels.FileChannel.open(
            path,
            if (readOnly) java.nio.file.StandardOpenOption.READ
            else java.nio.file.StandardOpenOption.READ
        )
        val id = nextId++
        channels[id] = channel
        return id
    }

    override fun close(handle: Long) {
        channels.remove(handle)?.close()
    }

    override fun pread(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
        val channel = channels[handle] ?: return -1
        val buffer = ByteBuffer.wrap(buf, offset, length)
        return channel.read(buffer, fileOffset)
    }

    override fun size(handle: Long): Long {
        return channels[handle]?.size() ?: -1
    }

    override fun read(handle: Long, buf: ByteArray, offset: Int, length: Int): Int {
        val channel = channels[handle] ?: return -1
        val buffer = ByteBuffer.wrap(buf, offset, length)
        return channel.read(buffer)
    }

    override fun seek(handle: Long, position: Long): Long {
        // FileChannel doesn't maintain a position for pwrite/pread,
        // so this is a no-op that returns the requested position
        return position
    }
}

/** JVM actual: returns NIO FileChannel implementation by default. */
actual fun platformSeekHandle(): SeekHandle = NioSeekHandle()

/** io_uring not available on JVM without JNI or JEP 436 preview. Returns null to use fallback. */
actual fun ioUringHandle(): SeekHandle? = null
