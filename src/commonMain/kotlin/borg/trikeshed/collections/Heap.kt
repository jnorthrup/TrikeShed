package borg.trikeshed.collections

import kotlin.jvm.JvmInline

/**
 * Binary min-heap as index arithmetic over slots the caller owns. [less] orders two slots and [swap]
 * exchanges them; both are lambda literals inlined at each call site, so every heap compiles to its
 * own monomorphic loop: no element objects, no boxing, no `Comparator` dispatch.
 */
inline fun heapUp(slot: Int, less: (Int, Int) -> Boolean, swap: (Int, Int) -> Unit) {
    var i = slot
    while (i > 0) {
        val p = (i - 1) ushr 1
        if (!less(i, p)) return
        swap(i, p); i = p
    }
}

inline fun heapDown(slot: Int, size: Int, less: (Int, Int) -> Boolean, swap: (Int, Int) -> Unit) {
    var i = slot
    while (true) {
        val l = 2 * i + 1
        if (l >= size) return
        val r = l + 1
        val c = if (r < size && less(r, l)) r else l
        if (!less(c, i)) return
        swap(i, c); i = c
    }
}

/**
 * A min-heap of (key, payload) Long pairs in one `LongArray`: slot 0 is the size, pair i sits at
 * `1 + 2i`. Ordered by key, then payload. A value class over the array: calls are static, the heap
 * is never boxed while it is used by its own type. A full heap does not grow: its owner spills
 * [popMin] into a sorted run (replacement selection) and [replaceMin] feeds the next key in place.
 */
@JvmInline
value class MinHeap(val slots: LongArray) {
    constructor(capacity: Int) : this(LongArray(1 + 2 * capacity))

    val size: Int get() = slots[0].toInt()
    val capacity: Int get() = (slots.size - 1) ushr 1
    val minKey: Long get() = slots[1]
    val minPayload: Long get() = slots[2]
    fun key(i: Int): Long = slots[1 + 2 * i]
    fun payload(i: Int): Long = slots[2 + 2 * i]

    fun push(key: Long, payload: Long) {
        val i = size
        require(i < capacity) { "heap full at $i" }
        slots[1 + 2 * i] = key; slots[2 + 2 * i] = payload; slots[0] = (i + 1).toLong()
        heapUp(i, { a, b -> before(a, b) }, { a, b -> exchange(a, b) })
    }

    /** Replaces the minimum with ([key], [payload]) and restores order: one sift, no pop/push pair. */
    fun replaceMin(key: Long, payload: Long) {
        slots[1] = key; slots[2] = payload
        heapDown(0, size, { a, b -> before(a, b) }, { a, b -> exchange(a, b) })
    }

    /** Removes the minimum; read [minKey]/[minPayload] first. */
    fun popMin() {
        val n = size - 1
        slots[1] = slots[1 + 2 * n]; slots[2] = slots[2 + 2 * n]; slots[0] = n.toLong()
        heapDown(0, n, { a, b -> before(a, b) }, { a, b -> exchange(a, b) })
    }

    private fun before(a: Int, b: Int): Boolean {
        val ka = slots[1 + 2 * a]; val kb = slots[1 + 2 * b]
        return ka < kb || ka == kb && slots[2 + 2 * a] < slots[2 + 2 * b]
    }

    private fun exchange(a: Int, b: Int) {
        val ia = 1 + 2 * a; val ib = 1 + 2 * b
        val k = slots[ia]; slots[ia] = slots[ib]; slots[ib] = k
        val p = slots[ia + 1]; slots[ia + 1] = slots[ib + 1]; slots[ib + 1] = p
    }
}

/**
 * k-way merge of sorted runs of packed keys into [out] (sized to the runs' total) through a [MinHeap]
 * of run heads, the run index as payload. Equal keys keep run order, so runs listed oldest first
 * emit their newest duplicate last. Returns the count written.
 */
fun mergeRuns(runs: Array<LongArray>, out: LongArray): Int {
    val heads = MinHeap(runs.size)
    val at = IntArray(runs.size)
    for (r in runs.indices) if (runs[r].isNotEmpty()) heads.push(runs[r][0], r.toLong())
    var n = 0
    while (heads.size > 0) {
        out[n++] = heads.minKey
        val r = heads.minPayload.toInt(); val next = ++at[r]
        if (next < runs[r].size) heads.replaceMin(runs[r][next], r.toLong()) else heads.popMin()
    }
    return n
}
