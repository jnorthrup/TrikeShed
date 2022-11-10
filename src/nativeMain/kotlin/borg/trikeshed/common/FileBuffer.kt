import borg.trikeshed.lib.Join
import borg.trikeshed.lib.logDebug
import kotlinx.cinterop.*

import simple.PosixFile
import simple.PosixOpenOpts

/**
 * an openable and closeable mmap file.
 */
actual class FileBuffer actual constructor(filename: String, initialOffset: Long, blkSize: Long, readOnly: Boolean) :
    Join<Long, (Long) -> Byte> {

    actual val filename: String
    actual val initialOffset: Long
    actual val blkSize: Long
    actual val readOnly: Boolean
    var buffer: COpaquePointer? = null


    init {
        this.filename = filename
        this.initialOffset = initialOffset
        this.blkSize = blkSize
        this.readOnly = readOnly
    }

    //    var jvmFile: java.io.RandomAccessFile? = null
    var file: PosixFile? = null

    //    var jvmMappedByteBuffer: java.nio.MappedByteBuffer? = null


    override val a: Long  //file size
        get() = file!!.size
    override val b: (Long) -> Byte
        get() = { index ->
            //convert  from buffer to COpaquePointer +index to get the byte
            (buffer!!.toLong() + index).toCPointer<ByteVar>()!!.pointed.value
        }

    actual fun close() {
        logDebug { "closing $filename" }

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
        buffer = file!!.mmap(if (blkSize == (-1L)) file!!.size.toULong() else blkSize.toULong(), offset = initialOffset)

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
}





