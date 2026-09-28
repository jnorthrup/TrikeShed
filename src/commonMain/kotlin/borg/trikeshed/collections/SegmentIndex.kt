package borg.trikeshed.collections

/**
 * Sorted-segment index over packed `Long` keys, one `Long` payload per key: an in-memory [MinHeap]
 * tail absorbs appends; when full it spills as a sorted run (a new segment). A key appended again
 * replaces its earlier payload: lookups read segments newest first, and [compact] k-way merges every
 * segment through [mergeRuns] keeping the newest payload per key. Segments are parallel primitive
 * columns, searched by binary search: no entry objects, no boxing.
 *
 * [segmentStats] is the distribution drilldown: per segment, its key range and row count, no scan.
 */
class SegmentIndex(tailCapacity: Int = 1 shl 14) {
    private val tail = MinHeap(tailCapacity)
    /** Segments oldest first; each is (keys, payloads) sorted by key, keys unique within a segment. */
    private val keys = ArrayList<LongArray>()
    private val payloads = ArrayList<LongArray>()
    /** Monotone sequence: rides in the tail's payload order so a later append of a key wins its spill. */
    private var sequence = 0L
    private val tailPayload = LongArray(tailCapacity)

    val segments: Int get() = keys.size
    val size: Int get() = keys.sumOf { it.size } + tail.size

    fun put(key: Long, payload: Long) {
        if (tail.size == tail.capacity) spill()
        val slot = (sequence++ % tailPayload.size).toInt()
        tailPayload[slot] = payload
        tail.push(key, sequence - 1)
    }

    /** The newest payload for [key], or [absent]. */
    fun get(key: Long, absent: Long = Long.MIN_VALUE): Long {
        var best = -1L; var found = absent
        for (i in 0 until tail.size) if (tail.key(i) == key && tail.payload(i) > best) { best = tail.payload(i); found = tailPayload[(best % tailPayload.size).toInt()] }
        if (best >= 0) return found
        for (s in keys.indices.reversed()) {
            val ks = keys[s]
            var lo = 0; var hi = ks.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val k = ks[mid]
                when { k < key -> lo = mid + 1; k > key -> hi = mid - 1; else -> return payloads[s][mid] }
            }
        }
        return absent
    }

    /** Drains the tail as one sorted segment; of repeated keys the latest append is kept. */
    fun spill() {
        val n = tail.size
        if (n == 0) return
        val ks = LongArray(n); val ps = LongArray(n)
        var m = 0
        while (tail.size > 0) {
            val k = tail.minKey; val seq = tail.minPayload
            tail.popMin()
            val p = tailPayload[(seq % tailPayload.size).toInt()]
            if (m > 0 && ks[m - 1] == k) ps[m - 1] = p else { ks[m] = k; ps[m] = p; m++ }
        }
        keys.add(ks.copyOf(m)); payloads.add(ps.copyOf(m))
    }

    /** Merges every segment into one, newest payload per key. */
    fun compact() {
        spill()
        if (keys.size <= 1) return
        // Pack (key, segment ordinal) so equal keys order oldest → newest, then keep the last.
        val total = keys.sumOf { it.size }
        val runs = Array(keys.size) { s -> keys[s] }
        val merged = LongArray(total)
        mergeRuns(runs, merged)
        // mergeRuns orders equal keys by run: walk each run's cursor in step to recover payloads.
        val at = IntArray(keys.size)
        val ks = LongArray(total); val ps = LongArray(total)
        var m = 0; var i = 0
        while (i < total) {
            val k = merged[i]; var p = 0L
            while (i < total && merged[i] == k) {
                var s = 0
                while (at[s] >= keys[s].size || keys[s][at[s]] != k) s++
                p = payloads[s][at[s]]; at[s]++; i++
            }
            ks[m] = k; ps[m] = p; m++
        }
        keys.clear(); payloads.clear()
        keys.add(ks.copyOf(m)); payloads.add(ps.copyOf(m))
    }

    /** Per segment, oldest first: first key, last key, row count. */
    fun segmentStats(): List<LongArray> = keys.map { longArrayOf(it.first(), it.last(), it.size.toLong()) }

    /** Every (key, payload) of the compacted index in key order, for writing out as a column file. */
    fun columns(): Pair<LongArray, LongArray> { compact(); return (keys.firstOrNull() ?: LongArray(0)) to (payloads.firstOrNull() ?: LongArray(0)) }

    /** Loads a previously written column pair as the oldest segment. */
    fun load(k: LongArray, p: LongArray) { require(k.size == p.size); if (k.isNotEmpty()) { keys.add(0, k); payloads.add(0, p) } }

    /**
     * Up to [limit] payloads whose keys lie in [lo]..[hi], in key order. Each segment is entered by
     * binary search and read forward; the unsorted tail is scanned. Hits land in a [MinHeap] bucket
     * sized to what was found, so only the hits are ordered, never the index.
     */
    fun range(lo: Long, hi: Long, limit: Int): LongArray {
        var found = 0
        for (i in 0 until tail.size) if (tail.key(i) in lo..hi) found++
        val starts = IntArray(keys.size)
        for (s in keys.indices) {
            val ks = keys[s]
            var a = 0; var b = ks.size
            while (a < b) { val m = (a + b) ushr 1; if (ks[m] < lo) a = m + 1 else b = m }
            starts[s] = a
            var j = a; while (j < ks.size && ks[j] <= hi && j - a < limit) j++
            found += j - a
        }
        if (found == 0) return LongArray(0)
        val bucket = MinHeap(found)
        for (i in 0 until tail.size) if (tail.key(i) in lo..hi) bucket.push(tail.key(i), tailPayload[(tail.payload(i) % tailPayload.size).toInt()])
        for (s in keys.indices) {
            val ks = keys[s]; val ps = payloads[s]
            var j = starts[s]; while (j < ks.size && ks[j] <= hi && j - starts[s] < limit) { bucket.push(ks[j], ps[j]); j++ }
        }
        val out = LongArray(minOf(limit, found))
        for (k in out.indices) { out[k] = bucket.minPayload; bucket.popMin() }
        return out
    }
}
