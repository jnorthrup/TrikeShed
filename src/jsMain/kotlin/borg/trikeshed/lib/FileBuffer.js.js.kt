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
    
    private val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly)

    actual override val a: Long get() = delegate.a
    actual override val b: (Long) -> Byte get() = delegate.b
    actual fun open() { delegate.open() }
    actual fun close() { delegate.close() }
    actual fun isOpen(): Boolean = delegate.isOpen()
    actual fun size(): Long = delegate.size()
    actual fun get(index: Long): Byte = delegate.get(index)
    actual fun put(index: Long, value: Byte) { delegate.put(index, value) }
}
