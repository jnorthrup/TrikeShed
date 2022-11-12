import borg.trikeshed.common.LongSeries
import borg.trikeshed.lib.debug
import borg.trikeshed.lib.logDebug
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


/**
 * an openable and closeable mmap file.
 *
 * get has no side effects but put has  undefined effects on size and sync
 */
actual class FileBuffer actual constructor(
    filename: String, initialOffset: Long,
    blkSize: Long, readOnly: Boolean,
) : LongSeries<Byte> {

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

    var jvmFile: java.io.RandomAccessFile? = null
    var jvmChannel: FileChannel? = null
    var jvmMappedByteBuffer: MappedByteBuffer? = null

    override val a: Long get() = jvmMappedByteBuffer!!.capacity().toLong()
    override val b: (Long) -> Byte get() = { index -> jvmMappedByteBuffer!!.get(index.toInt()) }

    actual fun close() {
        logDebug { "closing $filename" }
        jvmMappedByteBuffer?.force()
        jvmMappedByteBuffer?.clear()
        jvmMappedByteBuffer = null
        jvmChannel?.close()
        jvmChannel = null
        jvmFile?.close().debug { logDebug { "closed $filename" } }
        jvmFile = null
    }

    actual fun open() {
        logDebug { "opening $filename" }
        jvmFile = java.io.RandomAccessFile(filename, if (!readOnly) "rw" else "r")
            .debug { raf -> logDebug { "randomAccesFile: $raf" } }
        jvmChannel = jvmFile!!.channel.debug { thing -> logDebug { "fileChannel: $thing" } }
        jvmMappedByteBuffer = jvmChannel!!.map(
            (if (readOnly) FileChannel.MapMode.READ_ONLY else FileChannel.MapMode.READ_WRITE).debug { thing -> logDebug { "mapMode: $thing" } },
            initialOffset.debug { thing -> logDebug { "initialOffset: $thing" } },
            (if (blkSize == -1L) jvmChannel!!.size() - /*initial offset*/ initialOffset else blkSize).debug { thing -> logDebug { "blkSize: $thing" } }
        ).debug { thing: MappedByteBuffer -> logDebug { "blkSize: $thing" } }
    }

    actual fun isOpen(): Boolean = jvmMappedByteBuffer != null
    actual fun size(): Long = jvmMappedByteBuffer!!.capacity().toLong().debug { thing -> logDebug { "size: $thing" } }
    actual fun get(index: Long): Byte = jvmMappedByteBuffer!!.get(index.toInt())
    actual fun put(index: Long, value: Byte) {
        jvmMappedByteBuffer!!.put(index.toInt(), value)
    }

    companion object {
        fun open(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true): FileBuffer {
            logDebug { "opening $filename" }
            return FileBuffer(filename, initialOffset, blkSize, readOnly).apply {
                logDebug { "this isOpen()=${isOpen()}" }
                open().debug { logDebug { "call(ed) open()" } }
                logDebug { "this isOpen()=${isOpen()}" }
            }
        }
    }
}