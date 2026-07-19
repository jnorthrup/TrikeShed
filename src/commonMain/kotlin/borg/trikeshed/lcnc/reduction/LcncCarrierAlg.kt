package borg.trikeshed.lcnc.reduction

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * Carrier abstraction — all reduction pipelines operate on some Series-like carrier.
 *
 * Hot-path rule: [map] stays lazy (`size j` / α-shape). Only [filter]/[groupBy]/[sortBy]
 * reify — they change cardinality or order and cannot stay pure index-oracle without work.
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

/** Freeze known-size carrier into ArrayList once (sort / group values). */
private fun <T> freezeList(size: Int, get: (Int) -> T): ArrayList<T> {
    val out = ArrayList<T>(size)
    for (item in (size j get).view) out.add(item)
    return out
}

/**
 * Default implementation wrapping a Series.
 */
class SeriesCarrier<T>(private val series: Series<T>) : ReductionCarrier<T> {
    override val size: Int = series.size
    override fun get(index: Int): T = series[index]

    /** Lazy projection — no ArrayList. */
    override fun <U> map(transform: (T) -> U): ReductionCarrier<U> =
        SeriesCarrier(series α transform)

    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> {
        val out = ArrayList<T>(size)
        for (item in series.view) {
            if (predicate(item)) out.add(item)
        }
        return SeriesCarrier(listToSeries(out))
    }

    override fun <K> groupBy(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = linkedMapOf<K, MutableList<T>>()
        for (item in series.view) {
            groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K, V> groupBy(key: (T) -> K, value: (T) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (item in series.view) {
            groups.getOrPut(key(item)) { mutableListOf() }.add(value(item))
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K : Comparable<K>> sortBy(key: (T) -> K): ReductionCarrier<T> {
        val frozen = freezeList(size, series::get)
        frozen.sortBy(key)
        return SeriesCarrier(listToSeries(frozen))
    }

    override fun <Acc> fold(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (item in series.view) acc = folder.fold(acc, item)
        return acc
    }

    /** AbstractList facade over Series — no copy. */
    override fun toList(): List<T> = series.toList()
}

/**
 * RingSeries carrier — slab-aware for CRMS.
 */
class RingCarrier<T>(private val ring: Series<T>, private val slabSize: Int) : ReductionCarrier<T> {
    override val size: Int = minOf(ring.size, slabSize)
    override fun get(index: Int): T = ring[index]

    override fun <U> map(transform: (T) -> U): ReductionCarrier<U> =
        SeriesCarrier((size j ::get) α transform)

    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> {
        val out = ArrayList<T>(size)
        for (item in (size j ::get).view) {
            if (predicate(item)) out.add(item)
        }
        return SeriesCarrier(listToSeries(out))
    }

    override fun <K> groupBy(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = linkedMapOf<K, MutableList<T>>()
        for (item in (size j ::get).view) {
            groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K, V> groupBy(key: (T) -> K, value: (T) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (item in (size j ::get).view) {
            groups.getOrPut(key(item)) { mutableListOf() }.add(value(item))
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K : Comparable<K>> sortBy(key: (T) -> K): ReductionCarrier<T> {
        val frozen = freezeList(size, ::get)
        frozen.sortBy(key)
        return SeriesCarrier(listToSeries(frozen))
    }

    override fun <Acc> fold(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (item in (size j ::get).view) acc = folder.fold(acc, item)
        return acc
    }

    override fun toList(): List<T> = freezeList(size, ::get)
}

/**
 * Array carrier — for CRMS slab.
 */
class ArrayCarrier<T>(private val arr: Array<T>) : ReductionCarrier<T> {
    override val size: Int = arr.size
    override fun get(index: Int): T = arr[index]

    override fun <U> map(transform: (T) -> U): ReductionCarrier<U> =
        SeriesCarrier((size j { arr[it] }) α transform)

    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> {
        val out = ArrayList<T>(size)
        for (item in arr) if (predicate(item)) out.add(item)
        return SeriesCarrier(listToSeries(out))
    }

    override fun <K> groupBy(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = linkedMapOf<K, MutableList<T>>()
        for (item in arr) groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K, V> groupBy(key: (T) -> K, value: (T) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (item in arr) groups.getOrPut(key(item)) { mutableListOf() }.add(value(item))
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K : Comparable<K>> sortBy(key: (T) -> K): ReductionCarrier<T> {
        val frozen = ArrayList<T>(size).also { for (item in arr) it.add(item) }
        frozen.sortBy(key)
        return SeriesCarrier(listToSeries(frozen))
    }

    override fun <Acc> fold(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (item in arr) acc = folder.fold(acc, item)
        return acc
    }

    override fun toList(): List<T> = arr.asList()
}

/**
 * Cursor carrier — for Forge/Confix cursor operations. [Cursor] is `Series<RowVec>`.
 */
class CursorCarrier(private val cursor: Cursor) : ReductionCarrier<RowVec> {
    override val size: Int = cursor.size
    override fun get(index: Int): RowVec = cursor[index]

    override fun <U> map(transform: (RowVec) -> U): ReductionCarrier<U> =
        SeriesCarrier(cursor α transform)

    override fun filter(predicate: (RowVec) -> Boolean): ReductionCarrier<RowVec> {
        val out = ArrayList<RowVec>(size)
        for (row in cursor.view) {
            if (predicate(row)) out.add(row)
        }
        return SeriesCarrier(listToSeries(out))
    }

    override fun <K> groupBy(key: (RowVec) -> K): Map<K, ReductionCarrier<RowVec>> {
        val groups = linkedMapOf<K, MutableList<RowVec>>()
        for (row in cursor.view) {
            groups.getOrPut(key(row)) { mutableListOf() }.add(row)
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K, V> groupBy(key: (RowVec) -> K, value: (RowVec) -> V): Map<K, ReductionCarrier<V>> {
        val groups = linkedMapOf<K, MutableList<V>>()
        for (row in cursor.view) {
            groups.getOrPut(key(row)) { mutableListOf() }.add(value(row))
        }
        return groups.mapValues { (_, v) -> SeriesCarrier(listToSeries(v)) }
    }

    override fun <K : Comparable<K>> sortBy(key: (RowVec) -> K): ReductionCarrier<RowVec> {
        val frozen = freezeList(size, cursor::get)
        frozen.sortBy(key)
        return SeriesCarrier(listToSeries(frozen))
    }

    override fun <Acc> fold(initial: Acc, folder: Folder<RowVec, Acc>): Acc {
        var acc = initial
        for (row in cursor.view) acc = folder.fold(acc, row)
        return acc
    }

    override fun toList(): List<RowVec> = cursor.toList()
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

    fun <T> seriesCarrier(s: Series<T>): ReductionCarrier<T> = SeriesCarrier(s)

    fun <T> ringCarrier(ring: Series<T>, slabSize: Int): ReductionCarrier<T> = RingCarrier(ring, slabSize)

    fun <T> arrayCarrier(arr: Array<T>): ReductionCarrier<T> = ArrayCarrier(arr)

    fun cursorCarrier(c: Cursor): ReductionCarrier<RowVec> = CursorCarrier(c)

    @Suppress("UNCHECKED_CAST")
    fun <T> seriesCarrierAlg(): CarrierAlg<T> = object : CarrierAlg<T> {
        override val carrier: (Any) -> ReductionCarrier<T> = { input ->
            when (input) {
                is Array<*> -> ArrayCarrier(input as Array<T>)
                is Join<*, *> -> SeriesCarrier(input as Series<T>)
                else -> throw IllegalArgumentException("Unsupported carrier type")
            }
        }
    }
}

fun <T> emptySeriesCarrier(): ReductionCarrier<T> = SeriesCarrier(emptySeriesOf())
