@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.common

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Series2
import kotlinx.cinterop.*
import platform.posix.*
import simple.PosixOpenOpts

/**
 * Seek-based (non-mmap) file buffer for POSIX targets.
 *
 * Uses pread() for positional reads — no lseek needed, safe for scatter reads.
 * 64KB sliding window mirrors the JVM implementation.
 */
actual class SeekFileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
) : LongSeries<Byte> {

    private var fd: Int = -1
    private var fileSize: Long = 0L

    private val buf = ByteArray(BUFFER_SIZE)
    private var bufBase: Long = -1L
    private var bufLimit: Long = -1L

    companion object {
        const val BUFFER_SIZE = 65536
    }

    actual override val a: Long
        get() = if (blkSize == -1L) fileSize - initialOffset else blkSize

    actual override val b: (Long) -> Byte
        get() = { index ->
            val abs = initialOffset + index
            if (abs < bufBase || abs >= bufLimit) fillBuffer(abs)
            buf[(abs - bufBase).toInt()]
        }

    private fun fillBuffer(pos: Long) {
        val toRead = (fileSize - pos).coerceIn(0L, BUFFER_SIZE.toLong()).toInt()
        if (toRead == 0) throw IndexOutOfBoundsException("Position $pos beyond file size $fileSize")
        val read = buf.usePinned { pinned ->
            pread(fd, pinned.addressOf(0), toRead.convert(), pos)
        }
        if (read <= 0) throw IndexOutOfBoundsException("pread returned $read at pos $pos")
        bufBase = pos
        bufLimit = pos + read
    }

    actual fun open() {
        if (fd >= 0) return
        val flags = if (readOnly) PosixOpenOpts.withFlags(PosixOpenOpts.OpenReadOnly)
                    else PosixOpenOpts.withFlags(PosixOpenOpts.O_WrOnly)
        fd = platform.posix.open(filename, flags.toInt())
        require(fd >= 0) { "open($filename) failed" }
        memScoped {
            val st = alloc<stat>()
            fstat(fd, st.ptr)
            fileSize = st.st_size
        }
        bufBase = -1L
        bufLimit = -1L
    }

    actual fun close() {
        if (fd < 0) return
        platform.posix.close(fd)
        fd = -1
        bufBase = -1L
        bufLimit = -1L
    }

    actual fun isOpen(): Boolean = fd >= 0

    actual fun size(): Long = a

    actual fun get(index: Long): Byte = b(index)

    actual fun put(index: Long, value: Byte) {
        val abs = initialOffset + index
        byteArrayOf(value).usePinned { pinned ->
            pwrite(fd, pinned.addressOf(0), 1.convert(), abs)
        }
        if (abs >= bufBase && abs < bufLimit) buf[(abs - bufBase).toInt()] = value
    }

    actual fun seek(pos: Long) {
        fillBuffer(initialOffset + pos)
    }

    actual fun readv(requests: Series2<Long, ByteSeries>): IntArray {
        val results = IntArray(requests.a)
        for (i in 0 until requests.a) {
            val req = requests.b(i)
            val pos = req.a
            val dst = req.b
            val tmp = ByteArray(dst.rem)
            val read = tmp.usePinned { pinned ->
                pread(fd, pinned.addressOf(0), tmp.size.convert(), pos)
            }
            results[i] = if (read < 0) 0 else read.toInt()
        }
        return results
    }
}
