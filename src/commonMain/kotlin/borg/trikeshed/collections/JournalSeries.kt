@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import kotlinx.datetime.Clock

/**
 * A MutableSeries that records every mutation in a journal, enabling rollback.
 *
 * Each mutation produces a [JournalEntry] entry in the journal. [commit] clears the
 * journal (irreversible). [rollback] replays the journal in reverse to undo
 * all uncommitted mutations.
 */

data class JournalEntry<T>(
    val op: String,
    val index: Int,
    val old: T?,
    val new: T?,
    val timestamp: Long
)

class JournalSeries<T>(
    private val backing: COWArrayBackend<T> = COWArrayBackend(),
) : MutableSeries<T> {

    private val _journal: RecursiveMutableSeries<JournalEntry<T>> = RecursiveMutableSeries.create()

    fun journal(): Series<JournalEntry<T>> = _journal

    override val a: Int get() = backing.a
    override val b: (Int) -> T get() = backing.b

    override fun set(index: Int, item: T) {
        val old = backing[index]
        _journal.append(JournalEntry("SET", index, old, item, Clock.System.now().toEpochMilliseconds()))
        backing.set(index, item)
    }

    override fun append(item: T) {
        _journal.append(JournalEntry("ADD", backing.a, null, item, Clock.System.now().toEpochMilliseconds()))
        backing.append(item)
    }

    override fun insert(index: Int, item: T) {
        _journal.append(JournalEntry("ADD", index, null, item, Clock.System.now().toEpochMilliseconds()))
        backing.insert(index, item)
    }

    override fun removeAt(index: Int): T {
        val old = backing.removeAt(index)
        _journal.append(JournalEntry("REMOVE", index, old, null, Clock.System.now().toEpochMilliseconds()))
        return old
    }

    override fun remove(item: T): Boolean {
        val idx = (0 until backing.a).firstOrNull { backing[it] == item } ?: return false
        removeAt(idx)
        return true
    }

    override fun clear() {
        _journal.clear()
        val ts = Clock.System.now().toEpochMilliseconds()
        for (i in backing.a - 1 downTo 0) {
            _journal.append(JournalEntry("REMOVE", i, backing[i], null, ts))
        }
        backing.clear()
    }

    override fun freeze(): Series<T> = backing.freeze()
    override fun snapshot(): MutableSeries<T> {
        val snap = JournalSeries(backing.snapshot() as COWArrayBackend<T>); return snap
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

    fun commit() { _journal.clear() }

    fun rollback() {
        for (j in _journal.a - 1 downTo 0) {
            val d = _journal[j]
            when (d.op) {
                "SET" -> backing.set(d.index, d.old as T)
                "ADD" -> backing.removeAt(d.index)
                "REMOVE" -> backing.insert(d.index, d.old as T)
            }
        }
        _journal.clear()
    }

    val pendingCount: Int get() = _journal.a
    val hasPending: Boolean get() = _journal.a > 0
}
