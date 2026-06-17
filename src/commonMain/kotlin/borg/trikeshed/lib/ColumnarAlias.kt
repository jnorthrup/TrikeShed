@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")
package borg.trikeshed.lib

import borg.trikeshed.mutable.MutableSeries

/**
 * Primitive-specialized MutableSeries for Double.
 * Avoids boxing by using DoubleArray internally.
 */
class DoubleSeries(private var data: DoubleArray = DoubleArray(32)) : MutableSeries<Double> {
    private var _size = 0

    override val a: Int get() = _size
    override val b: (Int) -> Double = { i -> data[i] }

    override fun set(index: Int, item: Double) {
        require(index in 0 until _size)
        data[index] = item
    }

    override fun add(item: Double) {
        if (_size == data.size) data = data.copyOf(_size * 2)
        data[_size++] = item
    }

    override fun add(index: Int, item: Double) {
        require(index in 0.._size)
        if (_size == data.size) data = data.copyOf(_size * 2)
        for (i in _size downTo index + 1) data[i] = data[i - 1]
        data[index] = item
        _size++
    }

    override fun removeAt(index: Int): Double {
        require(index in 0 until _size)
        val removed = data[index]
        for (i in index until _size - 1) data[i] = data[i + 1]
        data[--_size] = 0.0
        return removed
    }

    override fun remove(item: Double): Boolean {
        val i = (0 until _size).firstOrNull { data[it] == item } ?: return false
        removeAt(i)
        return true
    }

    override fun clear() { _size = 0 }

    override fun plus(item: Double): MutableSeries<Double> { add(item); return this }
    override fun minus(item: Double): MutableSeries<Double> { remove(item); return this }
    override fun plusAssign(item: Double) { add(item) }
    override fun minusAssign(item: Double) { remove(item) }

    operator fun get(index: Int): Double = data[index]
}

/**
 * Primitive-specialized MutableSeries for Long.
 */
class LongBackingSeries(private var data: LongArray = LongArray(32)) : MutableSeries<Long> {
    private var _size = 0

    override val a: Int get() = _size
    override val b: (Int) -> Long = { i -> data[i] }

    override fun set(index: Int, item: Long) {
        require(index in 0 until _size)
        data[index] = item
    }

    override fun add(item: Long) {
        if (_size == data.size) data = data.copyOf(_size * 2)
        data[_size++] = item
    }

    override fun add(index: Int, item: Long) {
        require(index in 0.._size)
        if (_size == data.size) data = data.copyOf(_size * 2)
        for (i in _size downTo index + 1) data[i] = data[i - 1]
        data[index] = item
        _size++
    }

    override fun removeAt(index: Int): Long {
        require(index in 0 until _size)
        val removed = data[index]
        for (i in index until _size - 1) data[i] = data[i + 1]
        data[--_size] = 0L
        return removed
    }

    override fun remove(item: Long): Boolean {
        val i = (0 until _size).firstOrNull { data[it] == item } ?: return false
        removeAt(i)
        return true
    }

    override fun clear() { _size = 0 }

    override fun plus(item: Long): MutableSeries<Long> { add(item); return this }
    override fun minus(item: Long): MutableSeries<Long> { remove(item); return this }
    override fun plusAssign(item: Long) { add(item) }
    override fun minusAssign(item: Long) { remove(item) }

    operator fun get(index: Int): Long = data[index]
}

/**
 * Primitive-specialized MutableSeries for Int.
 */
class IntSeries(private var data: IntArray = IntArray(32)) : MutableSeries<Int> {
    private var _size = 0

    override val a: Int get() = _size
    override val b: (Int) -> Int = { i -> data[i] }

    override fun set(index: Int, item: Int) {
        require(index in 0 until _size)
        data[index] = item
    }

    override fun add(item: Int) {
        if (_size == data.size) data = data.copyOf(_size * 2)
        data[_size++] = item
    }

    override fun add(index: Int, item: Int) {
        require(index in 0.._size)
        if (_size == data.size) data = data.copyOf(_size * 2)
        for (i in _size downTo index + 1) data[i] = data[i - 1]
        data[index] = item
        _size++
    }

    override fun removeAt(index: Int): Int {
        require(index in 0 until _size)
        val removed = data[index]
        for (i in index until _size - 1) data[i] = data[i + 1]
        data[--_size] = 0
        return removed
    }

    override fun remove(item: Int): Boolean {
        val i = (0 until _size).firstOrNull { data[it] == item } ?: return false
        removeAt(i)
        return true
    }

    override fun clear() { _size = 0 }

    override fun plus(item: Int): MutableSeries<Int> { add(item); return this }
    override fun minus(item: Int): MutableSeries<Int> { remove(item); return this }
    override fun plusAssign(item: Int) { add(item) }
    override fun minusAssign(item: Int) { remove(item) }

    operator fun get(index: Int): Int = data[index]
}

/**
 * Primitive-specialized MutableSeries for Float.
 */
class FloatSeries(private var data: FloatArray = FloatArray(32)) : MutableSeries<Float> {
    private var _size = 0

    override val a: Int get() = _size
    override val b: (Int) -> Float = { i -> data[i] }

    override fun set(index: Int, item: Float) {
        require(index in 0 until _size)
        data[index] = item
    }

    override fun add(item: Float) {
        if (_size == data.size) data = data.copyOf(_size * 2)
        data[_size++] = item
    }

    override fun add(index: Int, item: Float) {
        require(index in 0.._size)
        if (_size == data.size) data = data.copyOf(_size * 2)
        for (i in _size downTo index + 1) data[i] = data[i - 1]
        data[index] = item
        _size++
    }

    override fun removeAt(index: Int): Float {
        require(index in 0 until _size)
        val removed = data[index]
        for (i in index until _size - 1) data[i] = data[i + 1]
        data[--_size] = 0.0f
        return removed
    }

    override fun remove(item: Float): Boolean {
        val i = (0 until _size).firstOrNull { data[it] == item } ?: return false
        removeAt(i)
        return true
    }

    override fun clear() { _size = 0 }

    override fun plus(item: Float): MutableSeries<Float> { add(item); return this }
    override fun minus(item: Float): MutableSeries<Float> { remove(item); return this }
    override fun plusAssign(item: Float) { add(item) }
    override fun minusAssign(item: Float) { remove(item) }

    operator fun get(index: Int): Float = data[index]
}

/** Type alias bridging from columnar naming to cursor/kernel naming. */
typealias CursorDoubleSeries = DoubleSeries
typealias CursorLongBackingSeries = LongBackingSeries
typealias CursorIntSeries = IntSeries
typealias CursorFloatSeries = FloatSeries