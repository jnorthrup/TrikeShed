package borg.trikeshed.lib

import borg.trikeshed.lib.long.LongSeries
import kotlin.coroutines.CoroutineContext

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongSeries<Byte>, CoroutineContext.Element {
    actual override val key: CoroutineContext.Key<*> get() = Key
    actual companion object Key : CoroutineContext.Key<FileBuffer>
    actual override val a: Long get() = TODO("WasmFileBuffer.a")
    actual override val b: (Long) -> Byte get() = { TODO("WasmFileBuffer.b") }
    actual fun open() { TODO("WasmFileBuffer.open") }
    actual fun close() { TODO("WasmFileBuffer.close") }
    actual fun isOpen(): Boolean = false
    actual fun size(): Long = 0
    actual fun get(index: Long): Byte = TODO("WasmFileBuffer.get")
    actual fun put(index: Long, value: Byte) { TODO("WasmFileBuffer.put") }
}
