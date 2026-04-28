@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

/**
 * Dense columnar MutableSeries backed by a primitive DoubleArray.
 *
 * O(1) get, O(1) set, O(n) add/remove/insert (realloc + copy).
 * Zero boxing on read path — [b] returns primitive Double, not boxed.
 *
 * Variants: [LongSeries], [IntSeries], [FloatSeries] follow the same pattern.
 */
class DoubleSeries(private var data: DoubleArray) : MutableSeries<Double> {

    constructor(initialCapacity: Int) : this(DoubleArray(initialCapacity) { 0.0 })

    override val a: Int get() = data.size
    override val b: (Int) -> Double = data::get

    val array: DoubleArray get() = data

    override fun set(index: Int, item: Double) {
        data[index] = item
    }

    override fun add(item: Double) {
        val newData = DoubleArray(data.size + 1)
        data.copyInto(newData)
        newData[data.size] = item
        data = newData
    }

    override fun add(index: Int, item: Double) {
        val newData = DoubleArray(data.size + 1)
        data.copyInto(newData, 0, 0, index)
        newData[index] = item
        data.copyInto(newData, index + 1, index, data.size)
        data = newData
    }

    override fun removeAt(index: Int): Double {
        val item = data[index]
        val newData = DoubleArray(data.size - 1)
        data.copyInto(newData, 0, 0, index)
        data.copyInto(newData, index, index + 1, data.size)
        data = newData
        return item
    }

    override fun remove(item: Double): Boolean {
        for (i in data.indices) {
            if (data[i] == item) {
                removeAt(i)
                return true
            }
        }
        return false
    }

    override fun clear() {
        data = DoubleArray(0)
    }

    override fun plus(item: Double): MutableSeries<Double> {
        add(item)
        return this
    }

    override fun minus(item: Double): MutableSeries<Double> {
        remove(item)
        return this
    }

    override fun plusAssign(item: Double) { add(item) }
    override fun minusAssign(item: Double) { remove(item) }
}

/**
 * Dense columnar MutableSeries backed by a primitive LongArray.
 */
class LongSeries(private var data: LongArray) : MutableSeries<Long> {

    constructor(initialCapacity: Int) : this(LongArray(initialCapacity) { 0L })

    override val a: Int get() = data.size
    override val b: (Int) -> Long = data::get

    override fun set(index: Int, item: Long) { data[index] = item }

    override fun add(item: Long) {
        val newData = LongArray(data.size + 1)
        data.copyInto(newData)
        newData[data.size] = item
        data = newData
    }

    override fun add(index: Int, item: Long) {
        val newData = LongArray(data.size + 1)
        data.copyInto(newData, 0, 0, index)
        newData[index] = item
        data.copyInto(newData, index + 1, index, data.size)
        data = newData
    }

    override fun removeAt(index: Int): Long {
        val item = data[index]
        val newData = LongArray(data.size - 1)
        data.copyInto(newData, 0, 0, index)
        data.copyInto(newData, index, index + 1, data.size)
        data = newData
        return item
    }

    override fun remove(item: Long): Boolean {
        for (i in data.indices) {
            if (data[i] == item) { removeAt(i); return true }
        }
        return false
    }

    override fun clear() { data = LongArray(0) }

    override fun plus(item: Long): MutableSeries<Long> { add(item); return this }
    override fun minus(item: Long): MutableSeries<Long> { remove(item); return this }
    override fun plusAssign(item: Long) { add(item) }
    override fun minusAssign(item: Long) { remove(item) }
}

/**
 * Dense columnar MutableSeries backed by a primitive IntArray.
 */
class IntSeries(private var data: IntArray) : MutableSeries<Int> {

    constructor(initialCapacity: Int) : this(IntArray(initialCapacity) { 0 })

    override val a: Int get() = data.size
    override val b: (Int) -> Int = data::get

    override fun set(index: Int, item: Int) { data[index] = item }

    override fun add(item: Int) {
        val newData = IntArray(data.size + 1)
        data.copyInto(newData)
        newData[data.size] = item
        data = newData
    }

    override fun add(index: Int, item: Int) {
        val newData = IntArray(data.size + 1)
        data.copyInto(newData, 0, 0, index)
        newData[index] = item
        data.copyInto(newData, index + 1, index, data.size)
        data = newData
    }

    override fun removeAt(index: Int): Int {
        val item = data[index]
        val newData = IntArray(data.size - 1)
        data.copyInto(newData, 0, 0, index)
        data.copyInto(newData, index, index + 1, data.size)
        data = newData
        return item
    }

    override fun remove(item: Int): Boolean {
        for (i in data.indices) {
            if (data[i] == item) { removeAt(i); return true }
        }
        return false
    }

    override fun clear() { data = IntArray(0) }

    override fun plus(item: Int): MutableSeries<Int> { add(item); return this }
    override fun minus(item: Int): MutableSeries<Int> { remove(item); return this }
    override fun plusAssign(item: Int) { add(item) }
    override fun minusAssign(item: Int) { remove(item) }
}

/**
 * Dense columnar MutableSeries backed by a primitive FloatArray.
 */
class FloatSeries(private var data: FloatArray) : MutableSeries<Float> {

    constructor(initialCapacity: Int) : this(FloatArray(initialCapacity) { 0f })

    override val a: Int get() = data.size
    override val b: (Int) -> Float = data::get

    override fun set(index: Int, item: Float) { data[index] = item }

    override fun add(item: Float) {
        val newData = FloatArray(data.size + 1)
        data.copyInto(newData)
        newData[data.size] = item
        data = newData
    }

    override fun add(index: Int, item: Float) {
        val newData = FloatArray(data.size + 1)
        data.copyInto(newData, 0, 0, index)
        newData[index] = item
        data.copyInto(newData, index + 1, index, data.size)
        data = newData
    }

    override fun removeAt(index: Int): Float {
        val item = data[index]
        val newData = FloatArray(data.size - 1)
        data.copyInto(newData, 0, 0, index)
        data.copyInto(newData, index, index + 1, data.size)
        data = newData
        return item
    }

    override fun remove(item: Float): Boolean {
        for (i in data.indices) {
            if (data[i] == item) { removeAt(i); return true }
        }
        return false
    }

    override fun clear() { data = FloatArray(0) }

    override fun plus(item: Float): MutableSeries<Float> { add(item); return this }
    override fun minus(item: Float): MutableSeries<Float> { remove(item); return this }
    override fun plusAssign(item: Float) { add(item) }
    override fun minusAssign(item: Float) { remove(item) }
}
