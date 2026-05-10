package borg.trikeshed

import borg.trikeshed.lib.FileBuffer
import borg.trikeshed.lib.LongSeries

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongSeries<Byte>, kotlin.coroutines.CoroutineContext.Element {
    actual override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
    actual companion object Key : kotlin.coroutines.CoroutineContext.Key<FileBuffer>
    actual override val a: Long get() = TODO("JvmFileBuffer.a")
    actual override val b: (Long) -> Byte get() = { TODO("JvmFileBuffer.b") }
    actual fun open() { TODO("JvmFileBuffer.open") }
    actual fun close() { TODO("JvmFileBuffer.close") }
    actual fun isOpen(): Boolean = false
    actual fun size(): Long = 0
    actual fun get(index: Long): Byte = TODO("JvmFileBuffer.get")
    actual fun put(index: Long, value: Byte) { TODO("JvmFileBuffer.put") }
}
