package borg.trikeshed.io

import borg.trikeshed.lib.LongSeries
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.file.*
import java.util.concurrent.atomic.AtomicLong

/**
 * An openable and closeable mmap file.
 *
 *  get has no side effects but put has undefined effects on size and sync
 */
actual class FileBuffer actual constructor(
    filename: String,
    initialOffset: Long,
    blkSize: Long,
    readOnly: Boolean,
) : LongSeries<Byte>, Usable {
    actual val filename: String = filename
    actual val initialOffset: Long = initialOffset
    actual val blkSize: Long = blkSize
    actual val readOnly: Boolean = readOnly
    private var open: Boolean = false
    actual override val a: Long = blkSize

    private var fileSize: Long = if (blkSize < 0) java.io.File(filename).length() else blkSize
    private var buffer: MappedByteBuffer = RandomAccessFile(filename, if (readOnly) "r" else "rw").channel.map(
        if (readOnly) java.nio.channels.FileChannel.MapMode.READ_ONLY else java.nio.channels.FileChannel.MapMode.READ_WRITE,
        initialOffset,
        fileSize
    )

    actual override fun close() {
        open = false
    }

    val epoch = AtomicLong(initialOffset)

    actual override fun open() {
        val file = java.io.File(filename)
        val randomAccessFile = RandomAccessFile(file, if (readOnly) "r" else "rw")
        val channel = randomAccessFile.channel
        buffer = channel.map(
            if (readOnly) java.nio.channels.FileChannel.MapMode.READ_ONLY else java.nio.channels.FileChannel.MapMode.READ_WRITE,
            initialOffset,
            Math.min(file.length() - initialOffset, Integer.MAX_VALUE.toLong())
        )
        open = true
        fileSize = buffer.capacity().toLong()
    }

    actual override val b: (Long) -> Byte = { index ->
        if (index < 0 || index >= fileSize) {
            throw IndexOutOfBoundsException("Index $index out of bounds for file size $fileSize")
        }
        buffer.get(index.toInt())
    }

    actual fun isOpen(): Boolean = open

    actual fun size(): Long = fileSize

    actual fun get(index: Long): Byte {
        if (!open) {
            throw IllegalStateException("File is not open")
        }
        if (index < 0 || index >= fileSize) {
            throw IndexOutOfBoundsException("Index $index out of bounds for file size $fileSize")
        }
        return buffer.get(index.toInt())
    }

    actual fun put(index: Long, value: Byte) {
        if (!open) {
            throw IllegalStateException("File is not open")
        }
        if (index < 0 || index >= fileSize) {
            throw IndexOutOfBoundsException("Index $index out of bounds for file size $fileSize")
        }
        buffer.put(index.toInt(), value)
    }
}