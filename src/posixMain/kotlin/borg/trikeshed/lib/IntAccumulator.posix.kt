@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.lib

import kotlinx.cinterop.*

actual class IntAccumulator actual constructor(initialCapacity: Int) : kotlin.coroutines.CoroutineContext.Element {
    actual override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
    actual companion object Key : kotlin.coroutines.CoroutineContext.Key<IntAccumulator>
   var buf: CArrayPointer<IntVar> = nativeHeap.allocArray(initialCapacity)
   var _size = 0
   var capacity = initialCapacity

    actual fun add(value: Int) {
        if (_size >= capacity) grow()
        buf[_size++] = value
    }

    actual val size: Int get() = _size

    actual fun toIntArray(): IntArray = IntArray(_size) { buf[it] }

    actual fun close() = nativeHeap.free(buf)

   fun grow() {
        val newCapacity = capacity * 2
        val newBuf = nativeHeap.allocArray<IntVar>(newCapacity)
        for (i in 0 until _size) newBuf[i] = buf[i]
        nativeHeap.free(buf)
        buf = newBuf
        capacity = newCapacity
    }
}