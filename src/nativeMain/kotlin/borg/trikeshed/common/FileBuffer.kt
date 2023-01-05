package borg.trikeshed.common


import borg.trikeshed.lib.debug
import borg.trikeshed.lib.logDebug
import borg.trikeshed.lib.`↺`

import simple.PosixFile
import simple.PosixOpenOpts
import kotlinx.cinterop.*
import platform.posix.munmap

/**
 * an openable and closeable mmap file.
 */
actual class FileBuffer actual constructor(//filename: String, initialOffset: Long, blkSize: Long, readOnly: Boolean)
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,

//    init {
//        this.filename = filename.debug { logDebug { "filename: $it" } }
//        this.initialOffset = initialOffset.debug {  logDebug("initialOffset: $it".`↺`) }
//        this.blkSize = blkSize .debug { logDebug("blkSize: $it".`↺`) }
//        this.readOnly = readOnly .debug { logDebug("readOnly: $it".`↺`) }
//    }
): LongSeries<Byte> {

    init{
        logDebug { "native FileBuffer: $filename, $initialOffset, $blkSize, $readOnly" }
    }
    var buffer: COpaquePointer? = null
    var file: PosixFile? = null

    override val a: Long by lazy {
        open()
        if (blkSize == (-1L)) file!!.size else blkSize
    }

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
        logDebug { "len: $len" }
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
}