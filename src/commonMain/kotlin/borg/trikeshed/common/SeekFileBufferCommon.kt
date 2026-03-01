package borg.trikeshed.common

/**
 * Common seek-based file buffer with windowed reads.
 *
 * Platform-agnostic: uses SeekHandle for all I/O.
 * JVM actual uses FileChannelSeekHandle.
 * POSIX actual uses PreadSeekHandle (pread for thread-safe random access).
 * Linux opt-in uses IoUringSeekHandle with batching.
 *
 * For bulk sequential scans, use FileBuffer with mmap.
 * For scattered ISAM reads, use SeekFileBuffer with elevator batching.
 */
class SeekFileBufferCommon(
    val filename: String,
    val initialOffset: Long = 0,
    val blkSize: Long = -1,
    val readOnly: Boolean = true,
    private val handle: SeekHandle = platformSeekHandle(),
) : LongSeries<Byte> {

    private var fd: Long = -1
    private var fileSize: Long = 0

    /** Windowed read buffer — 64KB amortizes syscalls, stays in L3. */
    private val window = ByteArray(BUFFER_SIZE)
    private var windowBase: Long = -1
    private var windowLimit: Long = -1
    private var windowPosition: Int = 0

    companion object {
        const val BUFFER_SIZE = 65536
    }

    override val a: Long
        get() = if (blkSize == -1L) fileSize - initialOffset else blkSize

    override val b: (Long) -> Byte
        get() = { index: Long ->
            val absolutePos = initialOffset + index
            if (absolutePos < windowBase || absolutePos >= windowLimit) {
                fillWindow(absolutePos)
            }
            window[(absolutePos - windowBase).toInt()]
        }

    fun open() {
        if (isOpen()) return
        fd = handle.open(filename, readOnly)
        fileSize = handle.size(fd)
        windowBase = -1
        windowLimit = -1
        windowPosition = 0
    }

    fun close() {
        if (!isOpen()) return
        handle.close(fd)
        fd = -1
        windowBase = -1
        windowLimit = -1
    }

    fun isOpen(): Boolean = fd >= 0

    fun size(): Long = a

    fun get(index: Long): Byte = b(index)

    fun put(index: Long, value: Byte) {
        // Write-through for now — could buffer for batching
        val buf = byteArrayOf(value)
        handle.pread(fd, buf, 0, 1, initialOffset + index) // Need pwrite variant
    }

    /** Seek to position — invalidates window. For external ISAM patterns. */
    fun seek(pos: Long) {
        val absolutePos = initialOffset + pos
        handle.seek(fd, absolutePos)
        windowBase = -1
        windowLimit = -1
        windowPosition = 0
    }

    /** Batch read scattered offsets — elevator sorted. */
    fun readv(requests: List<Pair<Long, ByteArray>>): IntArray {
        val results = IntArray(requests.size)
        if (requests.isEmpty()) return results

        // Elevator: sort by offset to minimize seeks
        val sorted = requests.withIndex().sortedBy { it.value.first }

        var totalRead = 0
        for ((idx, pair) in sorted) {
            val (offset, buf) = pair
            val absolutePos = initialOffset + offset
            val bytes = handle.pread(fd, buf, 0, buf.size, absolutePos)
            results[idx] = if (bytes < 0) 0 else bytes
            totalRead += results[idx]
        }
        return results
    }

    private fun fillWindow(pos: Long) {
        val remaining = (fileSize - pos).coerceAtLeast(0)
        val toRead = remaining.coerceAtMost(BUFFER_SIZE.toLong()).toInt()
        if (toRead == 0) {
            throw IndexOutOfBoundsException("Position $pos beyond file size $fileSize")
        }
        val bytesRead = handle.pread(fd, window, 0, toRead, pos)
        if (bytesRead <= 0) {
            throw IndexOutOfBoundsException("EOF at position $pos")
        }
        windowBase = pos
        windowLimit = pos + bytesRead
        windowPosition = bytesRead
    }
}
