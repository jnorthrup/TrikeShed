package borg.trikeshed.lib

import kotlin.coroutines.CoroutineContext

actual class FileBuffer actual constructor(
    actual val filename: String,
    actual val initialOffset: Long,
    actual val blkSize: Long,
    actual val readOnly: Boolean,
    actual val closeChannelOnMap: Boolean,
) : LongBackingSeries<Byte>, CoroutineContext.Element {
    actual override val key: CoroutineContext.Key<*> get() = Key
    actual companion object Key : CoroutineContext.Key<FileBuffer>
    actual override val a: Long get() = TODO("JsFileBuffer.a")
    actual override val b: (Long) -> Byte get() = { TODO("JsFileBuffer.b") }
    actual fun open() { TODO("JsFileBuffer.open") }
    actual fun close() { TODO("JsFileBuffer.close") }
    actual fun isOpen(): Boolean = false
    actual fun size(): Long = 0
    actual fun get(index: Long): Byte = TODO("JsFileBuffer.get")
    actual fun put(index: Long, value: Byte) { TODO("JsFileBuffer.put") }
    actual operator fun component1(): A {
        TODO("Not yet implemented")
    }

    actual operator fun component2(): B {
        TODO("Not yet implemented")
    }

    actual val pair: Pair<A, B>
        get() = TODO("Not yet implemented")
    actual val list: List<Any?>
        get() = TODO("Not yet implemented")
}
