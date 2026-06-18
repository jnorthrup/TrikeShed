@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")
package borg.trikeshed.lib

import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.CowArrayImpl

/**
 * Primitive-specialized MutableSeries for Double.
 * Avoids boxing by using DoubleArray internally.
 */
class DoubleSeries : MutableSeries<Double> {
    private val impl = CowArrayImpl<Double>()

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Double = impl.b

    override fun append(item: Double): MutableSeries<Double> = impl.append(item)
    override fun get(index: Int): Double = impl.get(index)
    override fun set(index: Int, item: Double): MutableSeries<Double> = impl.set(index, item)
    override fun add(item: Double): MutableSeries<Double> = impl.add(item)
    override fun add(index: Int, item: Double): MutableSeries<Double> = impl.add(index, item)
    override fun removeAt(index: Int): Double = impl.removeAt(index)
    override fun remove(item: Double): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Double> = impl.clear()

    override fun plus(item: Double): MutableSeries<Double> = impl.plus(item)
    override fun minus(item: Double): MutableSeries<Double> = impl.minus(item)
    override fun plusAssign(item: Double) { impl.plusAssign(item) }
    override fun minusAssign(item: Double) { impl.minusAssign(item) }

    override fun iterator(): Iterator<Double> = impl.iterator()
    override fun sequence(): Sequence<Double> = impl.sequence()
    override fun concat(other: MutableSeries<Double>): MutableSeries<Double> = impl.concat(other)

    operator fun get(index: Int): Double = impl.get(index)
    override fun set(index: Int, item: Double) { impl.set(index, item) }
    override fun add(item: Double) { impl.add(item) }
    override fun add(index: Int, item: Double) { impl.add(index, item) }
    override fun removeAt(index: Int): Double = impl.removeAt(index)
    override fun remove(item: Double): Boolean = impl.remove(item)
    override fun clear() { impl.clear() }
    override fun plus(item: Double): MutableSeries<Double> { impl.add(item); return this }
    override fun minus(item: Double): MutableSeries<Double> { impl.remove(item); return this }
    override fun plusAssign(item: Double) { impl.plusAssign(item) }
    override fun minusAssign(item: Double) { impl.minusAssign(item) }

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Double = impl.b

    override fun freeze(): MutableSeries<Double> = impl.freeze()
    override fun cowUpdate(index: Int, item: Double): MutableSeries<Double> = impl.cowUpdate(index, item)
    override fun cowSnapshot(): MutableSeries<Double> = impl.cowSnapshot()
    override fun insert(index: Int, item: Double): MutableSeries<Double> = impl.insert(index, item)
    override fun removeAt(index: Int): Pair<MutableSeries<Double>, Double> = impl.removeAt(index)
    override fun remove(item: Double): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Double> { impl.clear(); return this }
    override fun iterator(): Iterator<Double> = impl.iterator()
    override fun concat(other: MutableSeries<Double>): MutableSeries<Double> = impl.concat(other)
    override fun sequence(): Sequence<Double> = impl.sequence()
    override fun set(index: Int, item: Double): MutableSeries<Double> { this[index] = item; return this }
    override fun get(index: Int): Double = impl.get(index)
}

/**
 * Primitive-specialized MutableSeries for Long.
 */
class LongBackingSeries : MutableSeries<Long> {
    private val impl = CowArrayImpl<Long>()

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Long = impl.b

    override fun append(item: Long): MutableSeries<Long> = impl.append(item)
    override fun get(index: Int): Long = impl.get(index)
    override fun set(index: Int, item: Long): MutableSeries<Long> = impl.set(index, item)
    override fun add(item: Long): MutableSeries<Long> = impl.add(item)
    override fun add(index: Int, item: Long): MutableSeries<Long> = impl.add(index, item)
    override fun removeAt(index: Int): Long = impl.removeAt(index)
    override fun remove(item: Long): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Long> = impl.clear()
    override fun plus(item: Long): MutableSeries<Long> = impl.plus(item)
    override fun minus(item: Long): MutableSeries<Long> = impl.minus(item)
    override fun plusAssign(item: Long) { impl.plusAssign(item) }
    override fun minusAssign(item: Long) { impl.minusAssign(item) }
    override fun iterator(): Iterator<Long> = impl.iterator()
    override fun sequence(): Sequence<Long> = impl.sequence()
    override fun concat(other: MutableSeries<Long>): MutableSeries<Long> = impl.concat(other)

    override fun freeze(): MutableSeries<Long> = impl.freeze()
    override fun cowUpdate(index: Int, item: Long): MutableSeries<Long> = impl.cowUpdate(index, item)
    override fun cowSnapshot(): MutableSeries<Long> = impl.cowSnapshot()
    override fun insert(index: Int, item: Long): MutableSeries<Long> = impl.insert(index, item)
    override fun removeAt(index: Int): Pair<MutableSeries<Long>, Long> = impl.removeAt(index)
    override fun remove(item: Long): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Long> { impl.clear(); return this }
    override fun concat(other: MutableSeries<Long>): MutableSeries<Long> = impl.concat(other)
    override fun sequence(): Sequence<Long> = impl.sequence()

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Long = impl.b

    operator fun get(index: Int): Long = impl.get(index)
    override fun set(index: Int, item: Long): MutableSeries<Long> = impl.set(index, item)
    override fun add(item: Long) { impl.add(item) }
    override fun add(index: Int, item: Long) { impl.add(index, item) }
    override fun removeAt(index: Int): Long = impl.removeAt(index)
    override fun remove(item: Long): Boolean = impl.remove(item)
    override fun clear() { impl.clear() }
}

/**
 * Primitive-specialized MutableSeries for Int.
 */
class IntSeries : MutableSeries<Int> {
    private val impl = CowArrayImpl<Int>()

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Int = impl.b

    override fun append(item: Int): MutableSeries<Int> = impl.append(item)
    override fun get(index: Int): Int = impl.get(index)
    override fun set(index: Int, item: Int): MutableSeries<Int> = impl.set(index, item)
    override fun add(item: Int): MutableSeries<Int> = impl.add(item)
    override fun add(index: Int, item: Int): MutableSeries<Int> = impl.add(index, item)
    override fun removeAt(index: Int): Int = impl.removeAt(index)
    override fun remove(item: Int): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Int> = impl.clear()
    override fun plus(item: Int): MutableSeries<Int> = impl.plus(item)
    override fun minus(item: Int): MutableSeries<Int> = impl.minus(item)
    override fun plusAssign(item: Int) { impl.plusAssign(item) }
    override fun minusAssign(item: Int) { impl.minusAssign(item) }
    override fun iterator(): Iterator<Int> = impl.iterator()
    override fun sequence(): Sequence<Int> = impl.sequence()
    override fun concat(other: MutableSeries<Int>): MutableSeries<Int> = impl.concat(other)
    override fun freeze(): MutableSeries<Int> = impl.freeze()
    override fun cowUpdate(index: Int, item: Int): MutableSeries<Int> = impl.cowUpdate(index, item)
    override fun cowSnapshot(): MutableSeries<Int> = impl.cowSnapshot()
    override fun insert(index: Int, item: Int): MutableSeries<Int> = impl.insert(index, item)
    override fun removeAt(index: Int): Pair<MutableSeries<Int>, Int> = impl.removeAt(index)
    override fun remove(item: Int): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Int> { impl.clear(); return this }
    override fun concat(other: MutableSeries<Int>): MutableSeries<Int> = impl.concat(other)
    override fun sequence(): Sequence<Int> = impl.sequence()

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Int = impl.b

    operator fun get(index: Int): Int = impl.get(index)
    override fun set(index: Int, item: Int): MutableSeries<Int> = impl.set(index, item)
    override fun add(item: Int) { impl.add(item) }
    override fun add(index: Int, item: Int) { impl.add(index, item) }
    override fun removeAt(index: Int): Int = impl.removeAt(index)
    override fun remove(item: Int): Boolean = impl.remove(item)
    override fun clear() { impl.clear() }
}

/**
 * Primitive-specialized MutableSeries for Float.
 */
class FloatSeries : MutableSeries<Float> {
    private val impl = CowArrayImpl<Float>()

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Float = impl.b

    override fun append(item: Float): MutableSeries<Float> = impl.append(item)
    override fun get(index: Int): Float = impl.get(index)
    override fun set(index: Int, item: Float): MutableSeries<Float> = impl.set(index, item)
    override fun add(item: Float): MutableSeries<Float> = impl.add(item)
    override fun add(index: Int, item: Float): MutableSeries<Float> = impl.add(index, item)
    override fun removeAt(index: Int): Float = impl.removeAt(index)
    override fun remove(item: Float): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Float> = impl.clear()
    override fun plus(item: Float): MutableSeries<Float> = impl.plus(item)
    override fun minus(item: Float): MutableSeries<Float> = impl.minus(item)
    override fun plusAssign(item: Float) { impl.plusAssign(item) }
    override fun minusAssign(item: Float) { impl.minusAssign(item) }
    override fun iterator(): Iterator<Float> = impl.iterator()
    override fun sequence(): Sequence<Float> = impl.sequence()
    override fun concat(other: MutableSeries<Float>): MutableSeries<Float> = impl.concat(other)
    override fun freeze(): MutableSeries<Float> = impl.freeze()
    override fun cowUpdate(index: Int, item: Float): MutableSeries<Float> = impl.cowUpdate(index, item)
    override fun cowSnapshot(): MutableSeries<Float> = impl.cowSnapshot()
    override fun insert(index: Int, item: Float): MutableSeries<Float> = impl.insert(index, item)
    override fun removeAt(index: Int): Pair<MutableSeries<Float>, Float> = impl.removeAt(index)
    override fun remove(item: Float): Boolean = impl.remove(item)
    override fun clear(): MutableSeries<Float> { impl.clear(); return this }
    override fun concat(other: MutableSeries<Float>): MutableSeries<Float> = impl.concat(other)
    override fun sequence(): Sequence<Float> = impl.sequence()

    override val size: Int get() = impl.size
    override val a: Int get() = impl.a
    override val b: (Int) -> Float = impl.b

    operator fun get(index: Int): Float = impl.get(index)
    override fun set(index: Int, item: Float): MutableSeries<Float> = impl.set(index, item)
    override fun add(item: Float) { impl.add(item) }
    override fun add(index: Int, item: Float) { impl.add(index, item) }
    override fun removeAt(index: Int): Float = impl.removeAt(index)
    override fun remove(item: Float): Boolean = impl.remove(item)
    override fun clear() { impl.clear() }
}

/** Type alias bridging from columnar naming to cursor/kernel naming. */
typealias CursorDoubleSeries = DoubleSeries
typealias CursorLongBackingSeries = LongBackingSeries
typealias CursorIntSeries = IntSeries
typealias CursorFloatSeries = FloatSeries