package borg.trikeshed.lcnc.reduction

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * Carrier abstraction — all reduction pipelines operate on some Series-like carrier.
 */
interface ReductionCarrier<T> {
    val size: Int
    fun get(index: Int): T
    fun map<U>(transform: (T) -> U): ReductionCarrier<U>
    fun filter(predicate: (T) -> Boolean): ReductionCarrier<T>
    fun groupBy<K>(key: (T) -> K): Map<K, ReductionCarrier<T>>
    fun sortBy<K : Comparable<K>>(key: (T) -> K): ReductionCarrier<T>
    fun fold<Acc>(initial: Acc, folder: Folder<T, Acc>): Acc
    fun toList(): List<T>
}

/**
 * Default implementation wrapping a Series.
 */
class SeriesCarrier<T>(private val series: Series<T>) : ReductionCarrier<T> {
    override val size: Int = series.size
    override fun get(index: Int): T = series[index]
    override fun map<U>(transform: (T) -> U): ReductionCarrier<U> =
        SeriesCarrier(series.size j { i -> transform(series[i]) })
    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> =
        SeriesCarrier(series.size j { i -> series[i] }.filter { predicate(it) })
    override fun groupBy<K>(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = mutableMapOf<K, MutableList<T>>()
        for (i in 0 until size) {
            val item = series[i]
            val k = key(item)
            groups.getOrPut(k) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (k, v) -> SeriesCarrier(v.size j { i -> v[i] }) }
    }
    override fun sortBy<K : Comparable<K>>(key: (T) -> K): ReductionCarrier<T> =
        SeriesCarrier(series.toList().sortedBy(key).let { list -> list.size j { i -> list[i] } })
    override fun fold<Acc>(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (i in 0 until size) {
            acc = folder.fold(acc, series[i])
        }
        return acc
    }
    override fun toList(): List<T> = (0 until size).map { series[it] }
}

/**
 * RingSeries carrier — slab-aware for CRMS.
 */
class RingCarrier<T>(private val ring: RingSeries<T>, private val slabSize: Int) : ReductionCarrier<T> {
    override val size: Int = minOf(ring.count, slabSize)
    override fun get(index: Int): T = ring[(ring.head + index) % slabSize]
    override fun map<U>(transform: (T) -> U): ReductionCarrier<U> {
        val mapped = Array<U>(size) { transform(get(it)) }
        return ArrayCarrier(mapped)
    }
    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> {
        val filtered = mutableListOf<T>()
        for (i in 0 until size) {
            val item = get(i)
            if (predicate(item)) filtered.add(item)
        }
        return SeriesCarrier(filtered.size j { i -> filtered[i] })
    }
    override fun groupBy<K>(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = mutableMapOf<K, MutableList<T>>()
        for (i in 0 until size) {
            val item = get(i)
            groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (k, v) -> SeriesCarrier(v.size j { i -> v[i] }) }
    }
    override fun sortBy<K : Comparable<K>>(key: (T) -> K): ReductionCarrier<T> {
        val items = (0 until size).map { get(it) }.sortedBy(key)
        return SeriesCarrier(items.size j { i -> items[i] })
    }
    override fun fold<Acc>(initial: Acc, folder: Folder<T, Acc>): Acc {
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
    override fun map<U>(transform: (T) -> U): ReductionCarrier<U> =
        ArrayCarrier(arr.map(transform).toTypedArray())
    override fun filter(predicate: (T) -> Boolean): ReductionCarrier<T> =
        ArrayCarrier(arr.filter(predicate).toTypedArray())
    override fun groupBy<K>(key: (T) -> K): Map<K, ReductionCarrier<T>> {
        val groups = mutableMapOf<K, MutableList<T>>()
        for (item in arr) {
            groups.getOrPut(key(item)) { mutableListOf() }.add(item)
        }
        return groups.mapValues { (k, v) -> SeriesCarrier(v.size j { i -> v[i] }) }
    }
    override fun sortBy<K : Comparable<K>>(key: (T) -> K): ReductionCarrier<T> =
        ArrayCarrier(arr.sortedBy(key).toTypedArray())
    override fun fold<Acc>(initial: Acc, folder: Folder<T, Acc>): Acc {
        var acc = initial
        for (item in arr) {
            acc = folder.fold(acc, item)
        }
        return acc
    }
    override fun toList(): List<T> = arr.toList()
}

/**
 * Cursor carrier — for Forge/Confix cursor operations.
 */
class CursorCarrier(private val cursor: Cursor) : ReductionCarrier<RowVec> {
    override val size: Int = cursor.size
    override fun get(index: Int): RowVec = cursor[index]
    override fun map<U>(transform: (RowVec) -> U): ReductionCarrier<U> =
        SeriesCarrier(cursor.size j { i -> transform(cursor[i]) })
    override fun filter(predicate: (RowVec) -> Boolean): ReductionCarrier<RowVec> {
        val filtered = mutableListOf<RowVec>()
        for (i in 0 until size) {
            val row = cursor[i]
            if (predicate(row)) filtered.add(row)
        }
        return SeriesCarrier(filtered.size j { i -> filtered[i] })
    }
    override fun groupBy<K>(key: (RowVec) -> K): Map<K, ReductionCarrier<RowVec>> {
        val groups = mutableMapOf<K, MutableList<RowVec>>()
        for (i in 0 until size) {
            val row = cursor[i]
            groups.getOrPut(key(row)) { mutableListOf() }.add(row)
        }
        return groups.mapValues { (k, v) -> SeriesCarrier(v.size j { i -> v[i] }) }
    }
    override fun sortBy<K : Comparable<K>>(key: (RowVec) -> K): ReductionCarrier<RowVec> {
        val items = (0 until size).map { cursor[it] }.sortedBy(key)
        return SeriesCarrier(items.size j { i -> items[i] })
    }
    override fun fold<Acc>(initial: Acc, folder: Folder<RowVec, Acc>): Acc {
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

    /** RingSeries<T> → ReductionCarrier<T> (slab-aware) */
    fun <T> ringCarrier(ring: RingSeries<T>, slabSize: Int): ReductionCarrier<T> = RingCarrier(ring, slabSize)

    /** Array<T> → ReductionCarrier<T> (CRMS slab) */
    fun <T> arrayCarrier(arr: Array<T>): ReductionCarrier<T> = ArrayCarrier(arr)

    /** Cursor (= Series<RowVec>) → ReductionCarrier<RowVec> */
    fun cursorCarrier(c: Cursor): ReductionCarrier<RowVec> = CursorCarrier(c)

    /** Default carrier algebra for Series. */
    fun <T> seriesCarrierAlg(): CarrierAlg<T> = object : CarrierAlg<T> {
        override val carrier: (Any) -> ReductionCarrier<T> = { input ->
            when (input) {
                is Series<*> -> seriesCarrier(input as Series<T>)
                is Array<*> -> arrayCarrier(input as Array<T>)
                else -> throw IllegalArgumentException("Unsupported carrier type: ${input.javaClass}")
            }
        }
    }
}

/** Placeholder for RingSeries — actual type from lib_cursor. */
interface RingSeries<T> {
    val head: Int
    val count: Int
    operator fun get(index: Int): T
}