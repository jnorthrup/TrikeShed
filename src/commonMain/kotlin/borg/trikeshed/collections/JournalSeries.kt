@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.lib.Series
import kotlinx.datetime.Clock

/**
 * A MutableSeries that records every mutation in a journal, enabling rollback.
 *
 * Each mutation produces a [JournalEntry] entry in the journal. [commit] clears the
 * journal (irreversible). [rollback] replays the journal in reverse to undo
 * all uncommitted mutations, a [clear] among them.
 */

data class JournalEntry<T>(
    val op: String,
    val index: Int,
    val old: T?,
    val new: T?,
    val timestamp: Long
)

class JournalSeries<T>(
    val backing: COWArrayBackend<T> = COWArrayBackend(),
) : MutableSeries<T> {

    /** The uncommitted entries, oldest first. */
    val entries = SeriesBuffer<JournalEntry<T>>()

    fun journal(): Series<JournalEntry<T>> = entries

    override val a: Int get() = backing.a
    override val b: (Int) -> T get() = backing.b

    override fun set(index: Int, item: T) {
        val old = backing[index]
        entries.append(JournalEntry("SET", index, old, item, Clock.System.now().toEpochMilliseconds()))
        backing.set(index, item)
    }

    override fun append(item: T) {
        entries.append(JournalEntry("ADD", backing.a, null, item, Clock.System.now().toEpochMilliseconds()))
        backing.append(item)
    }

    override fun insert(index: Int, item: T) {
        entries.append(JournalEntry("ADD", index, null, item, Clock.System.now().toEpochMilliseconds()))
        backing.insert(index, item)
    }

    override fun removeAt(index: Int): T {
        val old = backing.removeAt(index)
        entries.append(JournalEntry("REMOVE", index, old, null, Clock.System.now().toEpochMilliseconds()))
        return old
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until backing.a) if (backing[i] == item) { removeAt(i); return true }
        return false
    }

    /** Journals each element's removal, last first, after the entries already pending, so [rollback] restores them all. */
    override fun clear() {
        val ts = Clock.System.now().toEpochMilliseconds()
        for (i in backing.a - 1 downTo 0) entries.append(JournalEntry("REMOVE", i, backing[i], null, ts))
        backing.clear()
    }

    override fun snapshot(): Series<T> = backing.snapshot()

    override fun iterator(): Iterator<T> = backing.iterator()

    fun commit() { entries.clear() }

    fun rollback() {
        for (j in entries.count - 1 downTo 0) {
            val d = entries.buf[j] as JournalEntry<T>
            when (d.op) {
                "SET" -> backing.set(d.index, d.old as T)
                "ADD" -> backing.removeAt(d.index)
                "REMOVE" -> backing.insert(d.index, d.old as T)
            }
        }
        entries.clear()
    }

    val pendingCount: Int get() = entries.count
    val hasPending: Boolean get() = entries.count > 0
}
