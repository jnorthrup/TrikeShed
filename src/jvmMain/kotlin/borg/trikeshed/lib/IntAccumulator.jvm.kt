package borg.trikeshed.lib

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT

actual class IntAccumulator actual constructor(initialCapacity: Int) : AutoCloseable {
    private var arena = Arena.ofConfined()
    private var seg = arena.allocate(initialCapacity.toLong() * Int.SIZE_BYTES)
    private var _size = 0
    private var capacity = initialCapacity

    actual fun add(value: Int) {
        if (_size >= capacity) grow()
        seg.setAtIndex(JAVA_INT, _size.toLong(), value)
        _size++
    }

    actual val size: Int get() = _size

    actual fun toIntArray(): IntArray = IntArray(_size) { seg.getAtIndex(JAVA_INT, it.toLong()) }

    actual override fun close() = arena.close()

    private fun grow() {
        val newCapacity = capacity * 2
        val newArena = Arena.ofConfined()
        val newSeg = newArena.allocate(newCapacity.toLong() * Int.SIZE_BYTES)
        for (i in 0 until _size) newSeg.setAtIndex(JAVA_INT, i.toLong(), seg.getAtIndex(JAVA_INT, i.toLong()))
        arena.close()
        arena = newArena
        seg = newSeg
        capacity = newCapacity
    }
}
