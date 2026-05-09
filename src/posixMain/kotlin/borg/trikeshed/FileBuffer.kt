@file:OptIn(ExperimentalForeignApi::class, ExperimentalForeignApi::class)

package borg.trikeshed

import borg.trikeshed.lib.logDebug
import kotlinx.cinterop.*
import platform.posix.munmap
import simple.PosixFile
import simple.PosixOpenOpts

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongSeries<Byte>, kotlin.coroutines.CoroutineContext.Element {
    actual override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
    actual companion object Key : kotlin.coroutines.CoroutineContext.Key<FileBuffer>

    init { logDebug { "native FileBuffer: $filename, $initialOffset, $blkSize, $readOnly" } }

    @OptIn(ExperimentalForeignApi::class)
    var buffer: COpaquePointer? = null
    var file: PosixFile? = null

    actual override val a: Long by lazy {
        open()
        if (blkSize == (-1L)) file!!.size else blkSize
    }

    @OptIn(ExperimentalForeignApi::class)
    actual override val b: (Long) -> Byte
        get() = { index: Long ->
            (buffer!!.toLong() + index).toCPointer<ByteVar>()!!.pointed.value
        }

    actual fun close() {
        logDebug { "closing $filename" }
        munmap(buffer, blkSize.toULong())
        file?.close()
        file = null; buffer = null
    }

    actual fun open(): Unit = memScoped {
        logDebug { "opening $filename" }
        file = PosixFile(filename,
            if (readOnly) PosixOpenOpts.withFlags(PosixOpenOpts.OpenReadOnly)
            else PosixOpenOpts.withFlags(PosixOpenOpts.O_Rdwr))
        val len: ULong = if (blkSize == (-1L)) file!!.size.toULong() else blkSize.toULong()
        buffer = file!!.mmap(len, offset = initialOffset)
    }

    actual fun isOpen(): Boolean = buffer != null
    actual fun size(): Long = file!!.size

    actual fun get(index: Long): Byte =
        (buffer!!.toLong() + index).toCPointer<ByteVar>()!!.pointed.value

    actual fun put(index: Long, value: Byte) {
        (buffer!!.toLong() + index).toCPointer<ByteVar>()!!.pointed.value = value
    }
}
