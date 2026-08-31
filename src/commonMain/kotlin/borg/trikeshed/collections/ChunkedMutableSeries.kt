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
 * `chunks` stays exposed as a `Series<Series<T>>` view for callers that read it;
 * it is now a projection of the backing store rather than the store itself.
 *
 * @param chunkSize  number of elements per chunk (default 4096)
 */
class ChunkedMutableSeries<T>(
    private val chunkSize: Int = 4096,
) : MutableSeries<T> {

    init { require(chunkSize > 0) { "chunkSize must be positive" } }

    /** The real storage: a list of chunks, each a plain growable list. */
    private val store: ArrayList<ArrayList<T>> = ArrayList()

    var totalSize: Int = 0
        private set

    /** Read-only projection, so `.chunks` keeps working without being the store. */
    val chunks: Series<Series<T>>
        get() = store.size j { ci -> store[ci].let { c -> c.size j { i -> c[i] } } }

    /**
     * Cumulative chunk ends. Cached because it is consulted on every indexed
     * read; invalidated by anything that changes a chunk's length. Appends into
     * the last chunk update it in place rather than dropping it, so the common
     * path never rebuilds.
     */
    private var stairs: IntArray? = null

    private fun stairsOf(): IntArray {
        stairs?.let { return it }
        val s = IntArray(store.size)
        var acc = 0
        for (i in store.indices) { acc += store[i].size; s[i] = acc }
        stairs = s
        return s
    }

    private fun chunkIndexAndOffset(index: Int): Twin<Int> {
        if (index < 0 || index >= totalSize) {
            throw IndexOutOfBoundsException("index $index, total $totalSize")
        }
        val s = stairsOf()
        // Binary search for the first chunk whose cumulative end exceeds index.
        var lo = 0; var hi = s.size - 1; var ci = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid] > index) { ci = mid; hi = mid - 1 } else lo = mid + 1
        }
        if (ci < 0) throw IndexOutOfBoundsException("index $index, total $totalSize")
        val offset = if (ci == 0) index else index - s[ci - 1]
        return ci j offset
    }

    override val a: Int get() = totalSize
    override val b: (Int) -> T get() = { i ->
        val (ci, offset) = chunkIndexAndOffset(i)
        store[ci][offset]
    }

    override fun set(index: Int, item: T) {
        val (ci, offset) = chunkIndexAndOffset(index)
        store[ci][offset] = item          // no length change: stairs stay valid
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
        val (ci, offset) = chunkIndexAndOffset(index)
        store[ci].add(offset, item)
        stairs = null
        totalSize++
    }

    override fun removeAt(index: Int): T {
        val (ci, offset) = chunkIndexAndOffset(index)
        val item = store[ci].removeAt(offset)
        if (store[ci].isEmpty()) store.removeAt(ci)
        stairs = null
        totalSize--
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until totalSize) { if (b(i) == item) { removeAt(i); return true } }
        return false
    }

    override fun clear() {
        store.clear()
        stairs = null
        totalSize = 0
    }

    override fun freeze(): Series<T> {
        val flat = Array<Any?>(totalSize) { i -> b(i) }
        return FrozenArray(flat)
    }

    override fun snapshot(): MutableSeries<T> {
        // A snapshot must not alias: the old version shared the chunk series, so
        // later appends to the original were visible through the "snapshot".
        val snap = ChunkedMutableSeries<T>(chunkSize)
        for (c in store) snap.store.add(ArrayList(c))
        snap.totalSize = totalSize
        return snap
    }

    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit = {}
    override fun version(): Long = 0L
    override val isFrozen: Boolean get() = false
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0
        override fun hasNext() = i < totalSize
        override fun next() = b(i++)
    }
    override fun sequence(): Sequence<T> = Sequence { iterator() }
    override fun plus(other: MutableSeries<T>): MutableSeries<T> {
        val result = ChunkedMutableSeries<T>(chunkSize)
        for (i in 0 until totalSize) result.append(b(i))
        for (i in 0 until other.a) result.append(other.b(i))
        return result
    }
}
