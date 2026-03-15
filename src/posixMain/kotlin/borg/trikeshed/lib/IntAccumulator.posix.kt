@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.lib

import kotlinx.cinterop.*

actual class IntAccumulator actual constructor(initialCapacity: Int) {
    private var buf: CArrayPointer<IntVar> = nativeHeap.allocArray(initialCapacity)
    private var _size = 0
    private var capacity = initialCapacity

    actual fun add(value: Int) {
        if (_size >= capacity) grow()
        buf[_size++] = value
    }

    actual val size: Int get() = _size

    actual fun toIntArray(): IntArray = IntArray(_size) { buf[it] }

    actual fun close() = nativeHeap.free(buf)

    private fun grow() {
        val newCapacity = capacity * 2
        val newBuf = nativeHeap.allocArray<IntVar>(newCapacity)
        for (i in 0 until _size) newBuf[i] = buf[i]
        nativeHeap.free(buf)
        buf = newBuf
        capacity = newCapacity
    }
}
