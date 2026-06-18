@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.*
import borg.trikeshed.lib.get

// ──────────────────────────────────────────────────────────────────────────
//  MutableSeries — marker interface extending Series<T>
//  Each trait extends MutableSeries. Classes compose only what they need.
// ──────────────────────────────────────────────────────────────────────────

/** Marker. A MutableSeries IS a Series<T> = Join<Int, (Int) -> T>. */
interface MutableSeries<T> : Series<T>

/** Append to tail. IS a MutableSeries. */
interface Appendable<T> : MutableSeries<T> {
    fun add(item: T)
}

/** Index-based get/set. IS a MutableSeries. */
interface RandomAccess<T> : MutableSeries<T> {
    operator fun get(index: Int): T
    operator fun set(index: Int, item: T)
}

/** Insert at arbitrary index. IS a MutableSeries. */
interface Insertable<T> : RandomAccess<T> {
    fun add(index: Int, item: T)
}

/** Remove elements. IS a MutableSeries. */
interface Removable<T> : RandomAccess<T> {
    fun removeAt(index: Int): T
    fun remove(item: T): Boolean
    fun clear()
}

/** Freeze into immutable snapshot. IS a MutableSeries. */
interface Freezable<T> : MutableSeries<T> {
    fun freeze(): Series<T>
    val isFrozen: Boolean
}

/** COW observation. IS a MutableSeries. */
interface COWObservable<T> : MutableSeries<T> {
    fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit
    fun version(): Long
}

// ──────────────────────────────────────────────────────────────────────────
//  CowSeriesBody — the immutable letter
// ──────────────────────────────────────────────────────────────────────────

class CowSeriesBody<T>(
    val arr: Array<Any?> = emptyArray(),
) : Join<Int, (Int) -> T> {
    override val a: Int get() = arr.size
    override val b: (Int) -> T = { i -> arr[i] as T }

    fun set(index: Int, item: T): CowSeriesBody<T> {
        val copy = arr.copyOf(); copy[index] = item; return CowSeriesBody(copy)
    }
    fun add(item: T): CowSeriesBody<T> = insert(arr.size, item)
    fun insert(index: Int, item: T): CowSeriesBody<T> {
        val copy: Array<Any?> = Array(arr.size + 1) { i -> when {
            i < index -> arr[i]; i == index -> item; else -> arr[i - 1]
        }}
        return CowSeriesBody(copy)
    }
    fun removeAt(index: Int): CowSeriesBody<T> {
        val copy = Array<Any?>(arr.size - 1) { i -> if (i < index) arr[i] else arr[i + 1] }
        return CowSeriesBody(copy)
    }
    fun remove(item: T): CowSeriesBody<T> {
        val i = arr.indexOfFirst { it == item }; return if (i >= 0) removeAt(i) else this
    }
    fun clear(): CowSeriesBody<T> = CowSeriesBody(emptyArray())

    companion object {
        fun <T> of(vararg items: T): CowSeriesBody<T> = CowSeriesBody(arrayOf(*items))
    }
}

typealias COWSeriesBody<T> = CowSeriesBody<T>

// ──────────────────────────────────────────────────────────────────────────
//  CowSeriesHandle — COW envelope implementing all traits
// ──────────────────────────────────────────────────────────────────────────

class CowSeriesHandle<T>(
    private var letter: CowSeriesBody<T>,
    private var observer: ((Twin<Series<T>>) -> Unit)? = null,
    private var versionObserver: ((Twin<Long>) -> Unit)? = null,
) : Appendable<T>, RandomAccess<T>, Insertable<T>, Removable<T>, Freezable<T>, COWObservable<T> {

    private var version: Long = 0L

    override val a: Int get() = letter.a
    override val b: (Int) -> T = { i -> letter.b(i) }
    val size: Int get() = letter.a
    override val isFrozen: Boolean get() = false

    override operator fun get(index: Int): T = letter.b(index)
    override operator fun set(index: Int, item: T) { val old = letter; letter = old.set(index, item); bump(old) }

    override fun add(item: T) { val old = letter; letter = old.add(item); bump(old) }
    override fun add(index: Int, item: T) { val old = letter; letter = old.insert(index, item); bump(old) }
    override fun removeAt(index: Int): T { val old = letter; val r = old.b(index); letter = old.removeAt(index); bump(old); return r }
    override fun remove(item: T): Boolean { val old = letter; val i = (0 until old.a).firstOrNull { old.b(it) == item } ?: return false; letter = old.removeAt(i); bump(old); return true }
    override fun clear() { val old = letter; letter = old.clear(); bump(old) }

    override fun freeze(): Series<T> = letter.a j { i -> letter.b(i) }
    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit { val prior = this.observer; this.observer = observer; return { this.observer = prior } }
    override fun version(): Long = version

    // Backward compat operators
    operator fun plus(item: T): CowSeriesHandle<T> { add(item); return this }
    operator fun minus(item: T): CowSeriesHandle<T> { remove(item); return this }
    operator fun plusAssign(item: T) { add(item) }
    operator fun minusAssign(item: T) { remove(item) }

    private fun bump(old: CowSeriesBody<T>) {
        val oldVer = version; version = oldVer + 1
        observer?.invoke(old as Series<T> j letter as Series<T>)
        versionObserver?.invoke(Twin(oldVer, version))
    }

    companion object {
        fun <T> empty(): CowSeriesHandle<T> = CowSeriesHandle(CowSeriesBody())
        fun <T> of(vararg items: T): CowSeriesHandle<T> = CowSeriesHandle(CowSeriesBody.of(*items))
    }
}

typealias COWSeriesHandle<T> = CowSeriesHandle<T>

fun <T> cowSeriesHandle(): CowSeriesHandle<T> = CowSeriesHandle(CowSeriesBody())