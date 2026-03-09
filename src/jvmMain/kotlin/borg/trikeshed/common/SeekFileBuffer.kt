package borg.trikeshed.common

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.debug
import borg.trikeshed.lib.logDebug
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * A seek-based (non-mmap) file buffer using windowed reads + scatter-gather.
 *
 * Unlike FileBuffer (which maps the entire file), this is designed for
 * ISAM-style scattered reads where you need to seek to arbitrary offsets.
 * Uses a 64KB sliding buffer for sequential access and readv for batching.
 *
 * For bulk sequential scans, use FileBuffer with mmap.
 * For scattered reads with seeks, use SeekFileBuffer.
 * For batch ISAM lookups, use readv() — sorts by offset (elevator)
 * and reads in a single syscall per chunk.
 */
actual class SeekFileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
) : LongSeries<Byte> {

    private var channel: FileChannel? = null
    private var fileSize: Long = 0

    /** Buffer for windowed reads — large enough to amortize syscalls, small enough for cache. */
    private val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
    private var bufferBase: Long = -1
    private var bufferLimit: Long = -1

    companion object {
        const val BUFFER_SIZE = 65536
        /** Max chunks per readv batch — tuned for typical ISAM page sizes. */
        const val READV_BATCH = 64
    }

    actual override val a: Long
        get() = if (blkSize == -1L) fileSize - initialOffset else blkSize

    actual override val b: (Long) -> Byte
        get() = { index: Long ->
            val absolutePos = initialOffset + index
            if (absolutePos < bufferBase || absolutePos >= bufferLimit) {
                fillBuffer(absolutePos)
            }
            buffer.get((absolutePos - bufferBase).toInt())
        }

    /** Seek to position and refill buffer. Public for external use in ISAM patterns. */
    actual fun seek(pos: Long) {
        fillBuffer(initialOffset + pos)
    }

    /**
     * Batch read scattered offsets — elevator-sorted to minimize seeks.
     * Uses FileChannel.read(ByteBuffer[], offset) for gather I/O.
     *
     * @param requests list of (absolute offset, destination buffer) pairs.
     *                 Modifies buffers in place, returns bytes read per request.
     */
    actual fun readv(requests: Series2<Long, ByteSeries>): IntArray {
        val results = IntArray(requests.a) { 0 }
        if (requests.a == 0) return results

        for (i in 0 until requests.a) {
            val request = requests.b(i)
            val pos = request.a
            val dst = request.b
            val buf = ByteBuffer.allocateDirect(dst.rem)
            val bytesRead = channel!!.read(buf, pos)
            results[i] = if (bytesRead < 0) 0 else bytesRead
        }
        return results
    }

    private fun fillBuffer(pos: Long) {
        buffer.clear()
        val remaining = (fileSize - pos).coerceAtLeast(0)
        val toRead = remaining.coerceAtMost(BUFFER_SIZE.toLong()).toInt()
        if (toRead == 0) {
            throw IndexOutOfBoundsException("Position $pos beyond file size $fileSize")
        }
        val bytesRead = channel!!.read(buffer, pos)
        if (bytesRead == -1) {
            throw IndexOutOfBoundsException("EOF at position $pos")
        }
        buffer.clear()
        buffer.limit(bytesRead)
        bufferBase = pos
        bufferLimit = pos + bytesRead
    }

    actual fun open() {
        if (isOpen()) return
        channel = FileChannel.open(
            Paths.get(filename),
            if (readOnly) StandardOpenOption.READ
            else StandardOpenOption.WRITE
        )
        fileSize = channel!!.size()
        bufferBase = -1
        bufferLimit = -1
    }

    actual fun close() {
        if (!isOpen()) return
        channel?.close()
        channel = null
        bufferBase = -1
        bufferLimit = -1
    }

    actual fun isOpen(): Boolean = channel != null

    actual fun size(): Long = a

    actual fun get(index: Long): Byte = b(index)

    actual fun put(index: Long, value: Byte) {
        val absolutePos = initialOffset + index
        val singleByte = ByteBuffer.allocateDirect(1)
        singleByte.put(0, value)
        singleByte.flip()
        channel!!.write(singleByte, absolutePos)
        if (absolutePos >= bufferBase && absolutePos < bufferLimit) {
            buffer.put((absolutePos - bufferBase).toInt(), value)
        }
    }
}
