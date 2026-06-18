@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j

/**
 * A MutableSeries that records every mutation in a journal, enabling rollback.
 *
 * Each mutation produces a [Delta] entry in the journal. [commit] clears the
 * journal (irreversible). [rollback] replays the journal in reverse to undo
 * all uncommitted mutations.
 */

/** One recorded mutation. */
sealed class Delta<out T> {
    data class Set<T>(val index: Int, val old: T, val new: T) : Delta<T>()
    data class Add<T>(val index: Int, val item: T) : Delta<T>()
    data class Remove<T>(val index: Int, val old: T) : Delta<T>()
}

class JournalSeries<T>(
    private val backing: COWArrayBackend<T> = COWArrayBackend(),
) : MutableSeries<T> {

    private val journal: RecursiveMutableSeries<Delta<T>> = RecursiveMutableSeries.create()

    override val a: Int get() = backing.a
    override val b: (Int) -> T get() = backing.b

    override fun set(index: Int, item: T) {
        val old = backing[index]
        journal.append(Delta.Set(index, old, item))
        backing.set(index, item)
    }

    override fun append(item: T) {
        journal.append(Delta.Add(backing.a, item))
        backing.append(item)
    }

    override fun insert(index: Int, item: T) {
        journal.append(Delta.Add(index, item))
        backing.insert(index, item)
    }

    override fun removeAt(index: Int): T {
        val old = backing.removeAt(index)
        journal.append(Delta.Remove(index, old))
        return old
    }

    override fun remove(item: T): Boolean {
        val idx = (0 until backing.a).firstOrNull { backing[it] == item } ?: return false
        removeAt(idx)
        return true
    }

    override fun clear() {
        journal.clear()
        for (i in backing.a - 1 downTo 0) {
            journal.append(Delta.Remove(i, backing[i]))
        }
        backing.clear()
    }

    override fun freeze(): Series<T> = backing.freeze()
    override fun cowSnapshot(): MutableSeries<T> {
        val snap = JournalSeries(backing.cowSnapshot() as COWArrayBackend<T>); return snap
    }
    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit = {}
    override fun version(): Long = 0L
    override val isFrozen: Boolean get() = false
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0; override fun hasNext() = i < backing.a
        override fun next() = backing[i++]
    }
    override fun sequence(): Sequence<T> = Sequence { iterator() }
    override fun plus(other: MutableSeries<T>): MutableSeries<T> {
        val result = JournalSeries<T>()
        for (i in 0 until backing.a) result.append(backing[i])
        for (i in 0 until other.a) result.append(other.b(i))
        return result
    }

    override fun plus(item: T): MutableSeries<T> { append(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }

    fun commit() { journal.clear() }

    fun rollback() {
        for (j in journal.a - 1 downTo 0) {
            when (val d = journal[j]) {
                is Delta.Set -> backing.set(d.index, d.old)
                is Delta.Add -> backing.removeAt(d.index)
                is Delta.Remove -> backing.insert(d.index, d.old)
            }
        }
        journal.clear()
    }

    val pendingCount: Int get() = journal.a
    val hasPending: Boolean get() = journal.a > 0
}
