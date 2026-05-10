package borg.trikeshed.lib

import borg.trikeshed.lib.long.LongSeries
import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.Channel
import borg.trikeshed.userspace.Channels
import borg.trikeshed.userspace.File
import borg.trikeshed.userspace.Files
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Common seek-based file buffer with windowed reads.
 *
 * Routes ALL I/O through [Channel] → [FunctionalUringFacade].
 * On Linux: io_uring SQEs or pread/pwrite fallback.
 * On JVM/JS/Wasm: platform backend via the same facade.
 *
 * For scattered ISAM reads, use [readv] with elevator batching.
 */
class SeekFileBufferCommon(
    val filename: String,
    val initialOffset: Long = 0,
    val blkSize: Long = -1,
    val readOnly: Boolean = true,
) : LongSeries<Byte>, Usable {

    private var file: File? = null
    private var channel: Channel? = null
    private var fileSize: Long = 0

    /** Windowed read buffer — 64KB amortizes syscalls, stays in L3. */
    val window = ByteArray(BUFFER_SIZE)
    var windowBase: Long = -1
    var windowLimit: Long = -1
    var windowPosition: Int = 0

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

    override fun open() {
        if (isOpen()) return
        val f = Files.open(filename, readOnly)
        file = f
        channel = Channels.open()
        fileSize = f.size()
        windowBase = -1
        windowLimit = -1
        windowPosition = 0
    }

    override fun close() {
        if (!isOpen()) return
        file?.close()
        file = null
        channel = null
        windowBase = -1
        windowLimit = -1
    }

    fun isOpen(): Boolean = file?.isOpen() == true

    fun size(): Long = a

    fun get(index: Long): Byte = b(index)

    fun put(index: Long, value: Byte) {
        val ch = channel ?: error("not open")
        val f = file ?: error("not open")
        val buf = ByteBuffer.wrap(byteArrayOf(value))
        ch.write(f, buf, initialOffset + index, 0L)
        ch.submit()
        ch.wait(1)
    }

    /** Seek to position — invalidates window. */
    fun seek(pos: Long) {
        windowBase = -1
        windowLimit = -1
        windowPosition = 0
    }

    /** Batch read scattered offsets in request order. */
    fun readv(requests: Series2<Long, ByteRegion>): IntArray {
        val ch = channel ?: error("not open")
        val f = file ?: error("not open")
        val results = IntArray(requests.a)
        if (requests.a == 0) return results
        for (idx in 0 until requests.a) {
            val request = requests.b(idx)
            val offset = request.a
            val dst = request.b
            val absolutePos = initialOffset + offset
            val buf = dst.asByteBuffer()
            ch.read(f, buf, absolutePos, idx.toLong())
        }
        ch.submit()
        val completed = ch.wait(requests.a)
        for (cqe in completed) {
            val idx = cqe.userData.toInt()
            if (idx in results.indices) results[idx] = if (cqe.res < 0) 0 else cqe.res
        }
        return results
    }

    fun fillWindow(pos: Long) {
        val ch = channel ?: error("not open")
        val f = file ?: error("not open")
        val remaining = (fileSize - pos).coerceAtLeast(0)
        val toRead = remaining.coerceAtMost(BUFFER_SIZE.toLong()).toInt()
        if (toRead == 0) {
            throw IndexOutOfBoundsException("Position $pos beyond file size $fileSize")
        }
        val buf = ByteBuffer.wrap(window, 0, toRead)
        ch.read(f, buf, pos, 1L)
        ch.submit()
        val cqe = ch.wait(1).firstOrNull()
        val bytesRead = cqe?.res ?: -1
        if (bytesRead <= 0) {
            throw IndexOutOfBoundsException("EOF at position $pos")
        }
        windowBase = pos
        windowLimit = pos + bytesRead
        windowPosition = bytesRead
    }
}
