package borg.trikeshed.og1.shape

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series

/* ── RingSeries — pulsing synapse ─────────────────────────────────────
 *
 *  Accumulates events. Fires when capacity reached OR on tick().
 *  After fire: shims reset, body ranked by eigenvalue, oldest evicted.
 *  The pulse IS the Confix re-join signal.
 *
 *  Facets:
 *    shims  — head/tail (zero-GC, reset per pulse)
 *    data   — ArrayList<RowVec> (ranked, evicted by re-join)
 *    pulse  — () -> Unit  (the Confix re-join callback)
 */

class RingSeries<E>(
    private val capacity: Int,
    private val data: ArrayList<E> = ArrayList(capacity),
    private val onPulse: (List<E>) -> Unit = {},
) {
    private var head: Long = 0L
    private var tail: Long = 0L

    val size: Int get() = minOf((head - tail).toInt(), capacity)
    val isFull: Boolean get() = size >= capacity
    val remaining: Int get() = capacity - size

    /** Append. Fires automatically when capacity reached. */
    fun append(e: E): Boolean {
        data.add(e)
        head++
        if (isFull) pulse()
        return true
    }

    /** AppendOrEvict — fires when full, oldest evicted into re-join. */
    fun appendOrEvict(e: E): E? {
        val evicted = if (isFull) data.removeAt(0) else null
        data.add(e)
        head++
        if (isFull) pulse()
        return evicted
    }

    /** Fire. Resets shims, sends ranked data to re-join. */
    fun pulse() {
        if (data.isEmpty()) return
        onPulse(data.toList())
        data.clear()
        head = 0L
        tail = 0L
    }

    fun advanceTail(count: Int) { tail += count }

    /** Shims only — for persistence across pulses. */
    fun resetShims() { head = 0L; tail = 0L }
    fun clear() { data.clear(); resetShims() }
    fun snapshot(): List<E> = data.toList()

    data class Shims(val head: Long, val tail: Long) {
        fun isClean(): Boolean = head == 0L && tail == 0L
    }
    fun shims(): Shims = Shims(head, tail)
}
