@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.get

/**
 * A MutableSeries that records every mutation in a journal, enabling rollback.
 *
 * Each mutation produces a [Delta] entry in the journal. [commit] clears the
 * journal (irreversible). [rollback] replays the journal in reverse to undo
 * all uncommitted mutations.
 *
 * Wraps a [CowSeriesHandle] — the backing is always immediately mutated.
 * The journal provides the undo capability.
 */

/** One recorded mutation. */
sealed class Delta<out T> {
    data class Set<T>(val index: Int, val old: T, val new: T) : Delta<T>()
    data class Add<T>(val index: Int, val item: T) : Delta<T>()
    data class Remove<T>(val index: Int, val old: T) : Delta<T>()
}

class JournalSeries<T>(
    private val backing: CowSeriesHandle<T> = CowSeriesHandle<T>(COWSeriesBody()),
) : Appendable<T>, RandomAccess<T>, Insertable<T>, Removable<T> {

    private val journal: RecursiveMutableSeries<Delta<T>> = RecursiveMutableSeries.create()

    // ── Series interface ─────────────────────────────────────────────────

    override val a: Int get() = backing.a
    override val b: (Int) -> T get() = backing.b

    // ── MutableSeries — all mutations journaled ──────────────────────────

    override fun set(index: Int, item: T) {
        val old = backing[index]
        journal.add(Delta.Set(index, old, item))
        backing.set(index, item)
    }

    override fun add(item: T) {
        journal.add(Delta.Add(backing.size, item))
        backing.add(item)
    }

    override fun add(index: Int, item: T) {
        journal.add(Delta.Add(index, item))
        backing.add(index, item)
    }

    override fun removeAt(index: Int): T {
        val old = backing.removeAt(index)
        journal.add(Delta.Remove(index, old))
        return old
    }

    override fun remove(item: T): Boolean {
        val idx = (0 until backing.size).firstOrNull { backing[it] == item } ?: return false
        removeAt(idx)
        return true
    }

    override fun clear() {
        // Discard prior journal entries so rollback only undoes the clear
        journal.clear()
        for (i in backing.size - 1 downTo 0) {
            journal.add(Delta.Remove(i, backing[i]))
        }
        backing.clear()
    }

    // ── Operator aliases ─────────────────────────────────────────────────

    fun plus(item: T): JournalSeries<T> { add(item); return this }
    fun minus(item: T): JournalSeries<T> { remove(item); return this }
    fun plusAssign(item: T) { add(item) }
    fun minusAssign(item: T) { remove(item) }

    // ── Transaction control ──────────────────────────────────────────────

    /** Irreversibly clear the journal. */
    fun commit() {
        journal.clear()
    }

    /** Undo all uncommitted mutations by replaying the journal in reverse. */
    fun rollback() {
        for (j in journal.size - 1 downTo 0) {
            when (val d = journal[j]) {
                is Delta.Set -> {
                    // Restore the old value
                    backing.set(d.index, d.old)
                }
                is Delta.Add -> {
                    // Remove what was added
                    backing.removeAt(d.index)
                }
                is Delta.Remove -> {
                    // Re-insert what was removed at its original position
                    backing.add(d.index, d.old)
                }
            }
        }
        journal.clear()
    }

    /** Number of uncommitted mutations. */
    val pendingCount: Int get() = journal.size

    /** True if there are uncommitted mutations. */
    val hasPending: Boolean get() = journal.size > 0
}