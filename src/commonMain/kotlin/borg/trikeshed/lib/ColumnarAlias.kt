@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")
package borg.trikeshed.lib

/**
 * Primitive-specialized series for columnar storage.
 */
class DoubleSeries {
    private var data = DoubleArray(32)
    private var _size = 0

    val size: Int get() = _size
    fun append(item: Double) {
        if (_size == data.size) data = data.copyOf(_size * 2)
        data[_size++] = item
    }
    operator fun get(index: Int): Double = data[index]
    fun set(index: Int, item: Double) { data[index] = item }
    fun clear() { _size = 0 }
    fun toDoubleArray(): DoubleArray = data.copyOf(_size)
}

typealias CursorDoubleSeries = DoubleSeries
typealias CursorLongBackingSeries = LongBackingSeries
typealias CursorIntSeries = IntSeries
typealias CursorFloatSeries = FloatSeries

class LongBackingSeries {
    private var data = LongArray(32)
    private var _size = 0
    val size: Int get() = _size
    fun append(item: Long) { if (_size == data.size) data = data.copyOf(_size * 2); data[_size++] = item }
    operator fun get(index: Int): Long = data[index]
    fun set(index: Int, item: Long) { data[index] = item }
    fun clear() { _size = 0 }
}

class IntSeries {
    private var data = IntArray(32)
    private var _size = 0
    val size: Int get() = _size
    fun append(item: Int) { if (_size == data.size) data = data.copyOf(_size * 2); data[_size++] = item }
    operator fun get(index: Int): Int = data[index]
    fun clear() { _size = 0 }
}

class FloatSeries {
    private var data = FloatArray(32)
    private var _size = 0
    val size: Int get() = _size
    fun append(item: Float) { if (_size == data.size) data = data.copyOf(_size * 2); data[_size++] = item }
    operator fun get(index: Int): Float = data[index]
    fun clear() { _size = 0 }
}