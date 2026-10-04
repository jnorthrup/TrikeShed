@file:Suppress("UNCHECKED_CAST")
package borg.trikeshed.collections

import borg.trikeshed.lib.*

/**
 * A MutableSeries backed by a tree of fixed-size chunks.
 *
 * Amortized O(1) append, O(1) read: the chunk table and the chunks themselves
 * are REAL storage, not composed lambdas.
 *
 * That distinction is the whole point of this file. The previous version built
 * every mutation out of `size j { … }` closures over the previous state:
 *
 *     val newLast = (lastChunk.size + 1) j { i -> if (i < lastChunk.size) lastChunk[i] else item }
 *     chunks = oldChunks.size j { i -> if (i == lastIdx) newLast else oldChunks[i] }
 *
 * so N appends left an N-deep closure chain — in the chunk table too, which
 * defeated chunking entirely — and every `get` walked all N frames. Reading was
 * O(N), filling was O(N²), and the stack grew without bound. It did not merely
 * lose to the O(n) copy it claimed to avoid; it hung. A thread dump of a stuck
 * `jvmTest` run was 1081 frames of exactly two alternating lines,
 * `JoinKt.get` ⇄ `ChunkedMutableSeries.append$lambda$2`, with 81 minutes of CPU
 * burned — the Confix parser benchmark never finished.
 *
 * Appends fill a chunk to [chunkSize] before opening the next, so while nothing has been inserted or removed in
 * the middle every chunk but the last is full and an index resolves by one division ([uniform]). A middle insert
 * or removal makes chunk lengths uneven; from then on an index resolves by binary search over the cached
 * cumulative chunk ends ([stairs]). [snapshot] and [iterator] walk the chunks in order rather than resolving
 * every index.
 *
 * `chunks` stays exposed as a `Series<Series<T>>` view for callers that read it;
 * it is a projection of the backing store rather than the store itself.
 *
 * @param chunkSize  number of elements per chunk (default 4096)
 */
class ChunkedMutableSeries<T>(
    val chunkSize: Int = 4096,
) : MutableSeries<T> {

    init { require(chunkSize > 0) { "chunkSize must be positive" } }

    /** The real storage: a list of chunks, each a plain growable list. */
    val store: ArrayList<ArrayList<T>> = ArrayList()

    var totalSize: Int = 0

    /** True while every chunk but the last holds exactly [chunkSize] elements. */
    var uniform: Boolean = true

    /** Read-only projection, so `.chunks` keeps working without being the store. */
    val chunks: Series<Series<T>>
        get() = store.size j { ci -> store[ci].let { c -> c.size j { i -> c[i] } } }

    /**
     * Cumulative chunk ends, consulted once chunks are uneven. Invalidated by anything that changes a chunk's
     * length; appends into the last chunk update it in place rather than dropping it.
     */
    var stairs: IntArray? = null

    fun stairsOf(): IntArray {
        stairs?.let { return it }
        val s = IntArray(store.size)
        var acc = 0
        for (i in store.indices) { acc += store[i].size; s[i] = acc }
        stairs = s
        return s
    }

    /** The chunk holding [index]. */
    fun chunkOf(index: Int): Int {
        if (index < 0 || index >= totalSize) throw IndexOutOfBoundsException("index $index, total $totalSize")
        if (uniform) return index / chunkSize
        val s = stairsOf()
        // The first chunk whose cumulative end exceeds index; chunks are never empty, so the ends strictly rise.
        var lo = 0; var hi = s.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid] > index) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** The index of chunk [ci]'s first element. */
    fun startOf(ci: Int): Int = if (uniform) ci * chunkSize else if (ci == 0) 0 else stairsOf()[ci - 1]

    override val a: Int get() = totalSize
    override val b: (Int) -> T = { i ->
        val ci = chunkOf(i)
        store[ci][i - startOf(ci)]
    }

    override fun set(index: Int, item: T) {
        val ci = chunkOf(index)
        store[ci][index - startOf(ci)] = item          // no length change: stairs stay valid
    }

    override fun append(item: T) {
        val last = store.lastOrNull()
        if (last == null || last.size >= chunkSize) {
            store.add(ArrayList<T>(if (store.isEmpty()) 1 else chunkSize).also { it.add(item) })
            stairs = null                 // a new chunk changes the table's shape
        } else {
            last.add(item)
            // Extend the cached tail in place — the hot path stays allocation-free.
            stairs?.let { it[it.size - 1] = it[it.size - 1] + 1 }
        }
        totalSize++
    }

    override fun insert(index: Int, item: T) {
        if (index == totalSize) { append(item); return }
        val ci = chunkOf(index)
        store[ci].add(index - startOf(ci), item)
        uniform = false
        stairs = null
        totalSize++
    }

    override fun removeAt(index: Int): T {
        val ci = chunkOf(index)
        val item = store[ci].removeAt(index - startOf(ci))
        if (store[ci].isEmpty()) store.removeAt(ci)
        // Removing the very last element leaves every earlier chunk as it was.
        if (index != totalSize - 1) uniform = false
        stairs = null
        totalSize--
        return item
    }

    override fun remove(item: T): Boolean {
        var base = 0
        for (c in store) {
            val j = c.indexOf(item)
            if (j >= 0) { removeAt(base + j); return true }
            base += c.size
        }
        return false
    }

    override fun clear() {
        store.clear()
        stairs = null
        uniform = true
        totalSize = 0
    }

    override fun snapshot(): Series<T> {
        val out = arrayOfNulls<Any?>(totalSize)
        var at = 0
        for (c in store) for (i in 0 until c.size) out[at++] = c[i]
        return FrozenArray(out)
    }

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var c = 0
        var i = 0
        var seen = 0
        override fun hasNext() = seen < totalSize
        override fun next(): T {
            while (i >= store[c].size) { c++; i = 0 }
            seen++
            return store[c][i++]
        }
    }
}
