package borg.trikeshed

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongSeries<Byte> {
    actual override val a: Long get() = TODO("FileBuffer.a")
    actual override val b: (Long) -> Byte get() = { TODO("FileBuffer.b") }
    actual fun open() { TODO("FileBuffer.open") }
    actual fun close() { TODO("FileBuffer.close") }
    actual fun isOpen(): Boolean = false
    actual fun size(): Long = 0
    actual fun get(index: Long): Byte = TODO("FileBuffer.get")
    actual fun put(index: Long, value: Byte) { TODO("FileBuffer.put") }
}
