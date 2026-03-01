package borg.trikeshed.common

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * an openable and closeable file buffer using MemorySegment (Java 22+).
 *
 * Maps the file into a single MemorySegment via Arena — no 2GB limit,
 * deterministic cleanup, and the OS handles paging.
 *
 * see FileBuffer.open
 */
actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean
) : LongSeries<Byte> {

    private var arena: Arena? = null
    private var segment: MemorySegment? = null
    private var fileSize: Long = 0

    actual override val a: Long get() = if (blkSize == -1L) fileSize - initialOffset else blkSize

    actual override val b: (Long) -> Byte
        get() = { index: Long ->
            segment!!.get(ValueLayout.JAVA_BYTE, initialOffset + index)
        }

    actual fun open() {
        if (isOpen()) return
        val path = Paths.get(filename)
        val channel = if (readOnly) {
            FileChannel.open(path, StandardOpenOption.READ)
        } else {
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
        }
        channel.use { ch ->
            fileSize = ch.size()
            val mapMode = if (readOnly) FileChannel.MapMode.READ_ONLY else FileChannel.MapMode.READ_WRITE
            arena = Arena.ofShared()
            segment = ch.map(mapMode, 0, fileSize, arena!!)
        }
    }

    actual fun close() {
        if (!isOpen()) return
        segment = null
        arena?.close()
        arena = null
    }

    actual fun isOpen(): Boolean = segment != null

    actual fun size(): Long = a

    actual fun get(index: Long): Byte = b(index)

    actual fun put(index: Long, value: Byte) {
        segment!!.set(ValueLayout.JAVA_BYTE, initialOffset + index, value)
    }
}
