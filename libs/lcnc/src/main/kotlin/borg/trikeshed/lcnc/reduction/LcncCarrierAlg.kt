package borg.trikeshed.lcnc.reduction

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * Carrier abstraction — all reduction pipelines operate on some Series-like carrier.
 */
interface ReductionCarrier<T> {
    val size: Int
    operator fun get(index: Int): T
    fun <U> map(transform: (T) -> U): ReductionCarrier<U>
    fun filter(predicate: (T) -> Boolean): ReductionCarrier<T>
    fun <K> groupBy(key: (T) -> K): Map<K, ReductionCarrier<T>>
    fun <K, V> groupBy(key: (T) -> K, value: (T) -> V): Map<K, ReductionCarrier<V>>
    fun <K : Comparable<K>> sortBy(key: (T) -> K): ReductionCarrier<T>
    fun <Acc> fold(initial: Acc, folder: Folder<T, Acc>): Acc
    fun toList(): List<T>
}

/** Build a Series from a List without reified-type pitfalls. */
private fun <T> listToSeries(list: List<T>): Series<T> = list.size j { i -> list[i] }

/**
 * Default implementation wrapping a Series.
 */
class SeriesCarrier<T>(private val series: Series<T>) : ReductionCarrier<T> {
    override val size: Int = series.size
    override fun get(index: Int): T = series[index]
    override fun <U> map(transform: (T) -> U): ReductionCarrier<U> {
        val out = ArrayList<U>(size)
        for (i in 0 until size) out.add(transform(series[i]))
        return SeriesCarrier(listToSeries(out))
    }
    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> {
        val out = ArrayList<T>(size)
        for (i in 0 until size) {
            val item = series[i]
            if (predicate(item)) out.add(item)
        }
        return SeriesCarrier(listToSeries(out))
    }
    override fun <K> groupBy(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = linkedMapOf<K, MutableList<T>>()
        for (i in 0 until size) {
            val item = series[i]
            groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K, V> groupBy(key: (T) -> K, value: (T) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (i in 0 until size) {
            val item = series[i]
            groups.getOrPut(key(item)) { mutableListOf() }.add(value(item))
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K : Comparable<K>> sortBy(key: (T) -> K): ReductionCarrier<T> {
        val sorted = (0 until size).map { series[it] }.sortedBy(key)
        return SeriesCarrier(listToSeries(sorted))
    }
    override fun <Acc> fold(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (i in 0 until size) {
            acc = folder.fold(acc, series[i])
        }
        return acc
    }
    override fun toList(): List<T> = (0 until size).map { series[it] }
}

/**
 * RingSeries carrier — slab-aware for CRMS. Wraps the real [RingSeries], which is a
 * [MutableSeries] (i.e. a [Series]); we use only Series-level access.
 */
class RingCarrier<T>(private val ring: Series<T>, private val slabSize: Int) : ReductionCarrier<T> {
    override val size: Int = minOf(ring.size, slabSize)
    override fun get(index: Int): T = ring[index]
    override fun <U> map(transform: (T) -> U): ReductionCarrier<U> {
        val out = ArrayList<U>(size)
        for (i in 0 until size) out.add(transform(get(i)))
        return SeriesCarrier(listToSeries(out))
    }
    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> {
        val out = ArrayList<T>(size)
        for (i in 0 until size) {
            val item = get(i)
            if (predicate(item)) out.add(item)
        }
        return SeriesCarrier(listToSeries(out))
    }
    override fun <K> groupBy(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = linkedMapOf<K, MutableList<T>>()
        for (i in 0 until size) {
            val item = get(i)
            groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K, V> groupBy(key: (T) -> K, value: (T) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (i in 0 until size) {
            val item = get(i)
            groups.getOrPut(key(item)) { mutableListOf() }.add(value(item))
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K : Comparable<K>> sortBy(key: (T) -> K): ReductionCarrier<T> {
        val sorted = (0 until size).map { get(it) }.sortedBy(key)
        return SeriesCarrier(listToSeries(sorted))
    }
    override fun <Acc> fold(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (i in 0 until size) {
            acc = folder.fold(acc, get(i))
        }
        return acc
    }
    override fun toList(): List<T> = (0 until size).map { get(it) }
}

/**
 * Array carrier — for CRMS slab.
 */
class ArrayCarrier<T>(private val arr: Array<T>) : ReductionCarrier<T> {
    override val size: Int = arr.size
    override fun get(index: Int): T = arr[index]
    override fun <U> map(transform: (T) -> U): ReductionCarrier<U> {
        val out = ArrayList<U>(size)
        for (item in arr) out.add(transform(item))
        return SeriesCarrier(listToSeries(out))
    }
    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> {
        val out = arr.filter(predicate)  // List<T>
        return SeriesCarrier(listToSeries(out))
    }
    override fun <K> groupBy(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = linkedMapOf<K, MutableList<T>>()
        for (item in arr) {
            groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K, V> groupBy(key: (T) -> K, value: (T) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (item in arr) {
            groups.getOrPut(key(item)) { mutableListOf() }.add(value(item))
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K : Comparable<K>> sortBy(key: (T) -> K): ReductionCarrier<T> {
        val sorted = arr.sortedBy(key)  // List<T>
        return SeriesCarrier(listToSeries(sorted))
    }
    override fun <Acc> fold(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (item in arr) {
            acc = folder.fold(acc, item)
        }
        return acc
    }
    override fun toList(): List<T> = arr.toList()
}

/**
 * Cursor carrier — for Forge/Confix cursor operations. [Cursor] is `Series<RowVec>`.
 */
class CursorCarrier(private val cursor: Cursor) : ReductionCarrier<RowVec> {
    override val size: Int = cursor.size
    override fun get(index: Int): RowVec = cursor[index]
    override fun <U> map(transform: (RowVec) -> U): ReductionCarrier<U> {
        val out = ArrayList<U>(size)
        for (i in 0 until size) out.add(transform(cursor[i]))
        return SeriesCarrier(listToSeries(out))
    }
    override fun filter(predicate: (RowVec) -> Boolean): ReductionCarrier<RowVec> {
        val out = ArrayList<RowVec>(size)
        for (i in 0 until size) {
            val row = cursor[i]
            if (predicate(row)) out.add(row)
        }
        return SeriesCarrier(listToSeries(out))
    }
    override fun <K> groupBy(key: (RowVec) -> K): Map<K, ReductionCarrier<RowVec>> {
        val groups = linkedMapOf<K, MutableList<RowVec>>()
        for (i in 0 until size) {
            val row = cursor[i]
            groups.getOrPut(key(row)) { mutableListOf() }.add(row)
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K, V> groupBy(key: (RowVec) -> K, value: (RowVec) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (i in 0 until size) {
            val row = cursor[i]
            groups.getOrPut(key(row)) { mutableListOf() }.add(value(row))
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }
    override fun <K : Comparable<K>> sortBy(key: (RowVec) -> K): ReductionCarrier<RowVec> {
        val sorted = (0 until size).map { cursor[it] }.sortedBy(key)
        return SeriesCarrier(listToSeries(sorted))
    }
    override fun <Acc> fold(initial: Acc, folder: Folder<RowVec, Acc>): Acc {
        var acc = initial
        for (i in 0 until size) {
            acc = folder.fold(acc, cursor[i])
        }
        return acc
    }
    override fun toList(): List<RowVec> = (0 until size).map { cursor[it] }
}

/**
 * Carrier algebra interface.
 */
interface CarrierAlg<V> {
    val carrier: (Any) -> ReductionCarrier<V>
}

/**
 * Adapters for existing carriers.
 */
object LcncCarrierAlg {

    /** Series<T> → ReductionCarrier<T> */
    fun <T> seriesCarrier(s: Series<T>): ReductionCarrier<T> = SeriesCarrier(s)

    /** RingSeries<T> → ReductionCarrier<T> (slab-aware). RingSeries is a Series<T>. */
    fun <T> ringCarrier(ring: Series<T>, slabSize: Int): ReductionCarrier<T> = RingCarrier(ring, slabSize)

    /** Array<T> → ReductionCarrier<T> (CRMS slab) */
    fun <T> arrayCarrier(arr: Array<T>): ReductionCarrier<T> = ArrayCarrier(arr)

    /** Cursor (= Series<RowVec>) → ReductionCarrier<RowVec> */
    fun cursorCarrier(c: Cursor): ReductionCarrier<RowVec> = CursorCarrier(c)

    /** Default carrier algebra for Series. */
    @Suppress("UNCHECKED_CAST")
    fun <T> seriesCarrierAlg(): CarrierAlg<T> = object : CarrierAlg<T> {
        override val carrier: (Any) -> ReductionCarrier<T> = { input ->
            when (input) {
                is Array<*> -> ArrayCarrier(input as Array<T>)
                is Join<*, *> -> SeriesCarrier(input as Series<T>)
                else -> throw IllegalArgumentException("Unsupported carrier type: ${input.javaClass}")
            }
        }
    }
}

/** Empty series carrier helper. */
fun <T> emptySeriesCarrier(): ReductionCarrier<T> = SeriesCarrier(emptySeriesOf())
