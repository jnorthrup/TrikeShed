@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib


/**
 * A MutableSeries that batches inserts and lazily merge-sorts.
 *
 * Maintains a [sorted] Series and a [pending] buffer. [add] appends to
 * pending. When pending reaches [mergeThreshold], [compact] is triggered:
 * pending is sorted, then merged into sorted.
 *
 * Between compactions, inserts are cheap (O(1) amortized). Reads always
 * come from the sorted backing.
 *
 * @param mergeThreshold  compact when pending reaches this size (default 64)
 * @param comparator      sort order for pending → sorted merge
 */
class MergeMutableSeries<T>(
    private val mergeThreshold: Int = 64,
    private val comparator: (T, T) -> Int,
) : MutableSeries<T> {

   var sorted: Series<T> = 0 j { throw IndexOutOfBoundsException("empty MergeMutableSeries") }
   val pending: RecursiveMutableSeries<T> = RecursiveMutableSeries.create()
   var totalSize: Int = 0

    init {
        require(mergeThreshold > 0) { "mergeThreshold must be positive" }
    }

    override val a: Int get() = totalSize
    override val b: (Int) -> T = { i ->
        // Read from sorted — pending is invisible until compacted
        require(i in 0 until sorted.size) { "index $i out of bounds [0, ${sorted.size})" }
        sorted[i]
    }

    override fun add(item: T) {
        pending.add(item)
        if (pending.size >= mergeThreshold) compact()
    }

    override fun add(index: Int, item: T) {
        // Ignore index — goes into pending for batch merge
        add(item)
    }

    override fun set(index: Int, item: T) {
        // Remove old from sorted, add new to pending
        val oldSorted = sorted
        sorted = (oldSorted.size - 1) j { i ->
            if (i < index) oldSorted[i] else oldSorted[i + 1]
        }
        pending.add(item)
    }

    override fun removeAt(index: Int): T {
        val item = sorted[index]
        val oldSorted = sorted
        sorted = (oldSorted.size - 1) j { i ->
            if (i < index) oldSorted[i] else oldSorted[i + 1]
        }
        totalSize--
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until sorted.size) {
            if (comparator(sorted[i], item) == 0) {
                removeAt(i)
                return true
            }
        }
        // Also check pending
        for (i in 0 until pending.size) {
            if (comparator(pending[i], item) == 0) {
                pending.removeAt(i)
                return true
            }
        }
        return false
    }

    override fun clear() {
        sorted = 0 j { throw IndexOutOfBoundsException("empty MergeMutableSeries") }
        pending.clear()
        totalSize = 0
    }

    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { add(item) }
    override fun minusAssign(item: T) { remove(item) }

    // ── Compaction ───────────────────────────────────────────────────────

    /**
     * Sort pending, then merge into sorted.
     *
     * Uses a simple two-pointer merge: both sorted and pending are assumed
     * to be individually sorted (pending via external sort, sorted by
     * construction). The merged result replaces sorted.
     */
    fun compact() {
        if (pending.size == 0) return

        val sortedPending = sortPending()  // pure array-backed series
        val sSize = sorted.size
        val pSize = sortedPending.size
        val n = sSize + pSize
        // Eagerly materialize to avoid double-access of mutable-counter lambda
        val arr = arrayOfNulls<Any?>(n)
        var si = 0; var pi = 0
        @Suppress("UNCHECKED_CAST")
        for (di in 0 until n) {
            arr[di] = when {
                si >= sSize -> sortedPending[pi++]
                pi >= pSize -> sorted[si++]
                comparator(sorted[si] as T, sortedPending[pi]) <= 0 -> sorted[si++]
                else -> sortedPending[pi++]
            }
        }
        @Suppress("UNCHECKED_CAST")
        sorted = n j { i -> arr[i] as T }
        totalSize = n
        pending.clear()
    }

    /** Simple insertion sort of the pending buffer. */
    private fun sortPending(): Series<T> {
        if (pending.size <= 1) return pending.data

        // Collect into an Array for in-place insertion sort
        val arr = arrayOfNulls<Any?>(pending.size)
        for (i in 0 until pending.size) arr[i] = pending[i]

        @Suppress("UNCHECKED_CAST")
        for (i in 1 until arr.size) {
            val key = arr[i] as T
            var j = i - 1
            while (j >= 0 && comparator(arr[j] as T, key) > 0) {
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = key
        }

        return arr.size j { i -> arr[i] as T }
    }

    /** Force compaction even if threshold not reached. */
    fun flush() = compact()
}
