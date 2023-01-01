import borg.trikeshed.lib.Join
import borg.trikeshed.lib.logDebug

import simple.PosixFile
import simple.PosixOpenOpts
import kotlinx.cinterop.*
import platform.posix.munmap

/**
 * an openable and closeable mmap file.
 */
actual class FileBuffer actual constructor(filename: String, initialOffset: Long, blkSize: Long, readOnly: Boolean) :
    borg.trikeshed.common.LongSeries<Byte>{

    actual val filename: String
    actual val initialOffset: Long
    actual val blkSize: Long
    actual val readOnly: Boolean

    init {
        this.filename = filename
        this.initialOffset = initialOffset
        this.blkSize = blkSize
        this.readOnly = readOnly
    }

    var buffer: COpaquePointer? = null
    var file: PosixFile? = null

    override val a: Long   = if (blkSize == (-1L)) file!!.size else blkSize

    override val b: (Long) -> Byte
        get() = { index: Long ->
            (buffer!!.toLong() + index).toCPointer<ByteVar>()!!.pointed.value
        }

    actual fun close() {
        logDebug { "closing $filename" }
        munmap(buffer, blkSize.toULong())
        file?.close()

        file = null
        buffer = null
    }

    actual fun open() = memScoped {
        logDebug { "opening $filename" }
        file = PosixFile(
            filename,
            if (readOnly) PosixOpenOpts.withFlags(PosixOpenOpts.OpenReadOnly)
            else PosixOpenOpts.withFlags(PosixOpenOpts.O_Rdwr),
        )
        val len: ULong = if (blkSize == (-1L)) file!!.size.toULong() else blkSize.toULong()
        buffer = file!!.mmap(len, offset = initialOffset)
    }

    actual fun isOpen(): Boolean {
        return buffer != null
    }

    actual fun size(): Long {
        return file!!.size
    }

    actual fun get(index: Long): Byte {
        return (buffer!!.toLong() + index).toCPointer<ByteVar>()!!.pointed.value
    }

    actual fun put(index: Long, value: Byte) {
        (buffer!!.toLong() + index).toCPointer<ByteVar>()!!.pointed.value = value
    }

    companion object {
        fun open(
            filename: String,
            initialOffset: Long = 0L,
            blkSize: Long = -1L,
            readOnly: Boolean = true,
        ): FileBuffer = FileBuffer(filename, initialOffset, blkSize, readOnly)
    }
}

actual fun openFileBuffer(
    filename: String,
    initialOffset: Long,
    blkSize: Long,
    readOnly: Boolean,
): FileBuffer {
    return FileBuffer.open(filename, initialOffset, blkSize, readOnly)
}