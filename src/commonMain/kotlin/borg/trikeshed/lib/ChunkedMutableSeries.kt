@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

import borg.trikeshed.collections.s_

/**
 * A MutableSeries backed by a tree of fixed-size chunks.
 *
 * Amortized O(1) append: pushes to the last chunk until full, then allocates
 * a new chunk. Avoids the O(n) copy that [RecursiveMutableSeries] and
 * [CowSeriesHandle] incur on every append.
 *
 * Read path: stairs-indexed via the same [combine] pattern used in [Combine.kt].
 * Set/removeAt: locate chunk + offset, copy that single chunk, rebuild combine view.
 *
 * @param chunkSize  number of elements per chunk (default 4096)
 */
class ChunkedMutableSeries<T>(
    private val chunkSize: Int = 4096,
) : MutableSeries<T> {

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

   var chunks: Series<Series<T>> = 0 j { throw IndexOutOfBoundsException("no chunks") }
   var totalSize: Int = 0

    // ── Stairs index for combine view ────────────────────────────────────

    private fun stairsArray(): IntArray {
        val sz = chunks.size
        if (sz == 0) return IntArray(0)
        val stairs = IntArray(sz)
        var acc = 0
        for (i in 0 until sz) {
            acc += chunks[i].size
            stairs[i] = acc
        }
        return stairs
    }

    private fun chunkIndexAndOffset(index: Int): Pair<Int, Int> {
        val stairs = stairsArray()
        if (stairs.isEmpty()) throw IndexOutOfBoundsException("index $index, empty series")
        val ci = stairs.indexOfFirst { it > index }
        if (ci < 0) throw IndexOutOfBoundsException("index $index, total $totalSize")
        val offset = if (ci == 0) index else index - stairs[ci - 1]
        return ci to offset
    }

    // ── Series interface ─────────────────────────────────────────────────

    override val a: Int get() = totalSize
    override val b: (Int) -> T = { i ->
        val (ci, offset) = chunkIndexAndOffset(i)
        chunks[ci][offset]
    }

    // ── MutableSeries ────────────────────────────────────────────────────

    override fun set(index: Int, item: T) {
        val (ci, offset) = chunkIndexAndOffset(index)
        // Rebuild the target chunk with the new value
        val oldChunk = chunks[ci]
        val newChunk: Series<T> = oldChunk.size j { i ->
            if (i == offset) item else oldChunk[i]
        }
        chunks = chunks.size j { i ->
            if (i == ci) newChunk else chunks[i]
        }
    }

    override fun add(item: T) {
        if (totalSize == 0) {
            // First chunk
            val firstChunk: Series<T> = 1 j { item }
            chunks = 1 j { firstChunk }
            totalSize = 1
            return
        }
        val lastIdx = chunks.size - 1
        val lastChunk = chunks[lastIdx]
        if (lastChunk.size < chunkSize) {
            // Append to existing last chunk
            val newLast: Series<T> = (lastChunk.size + 1) j { i ->
                if (i < lastChunk.size) lastChunk[i] else item
            }
            chunks = chunks.size j { i ->
                if (i == lastIdx) newLast else chunks[i]
            }
        } else {
            // Allocate new chunk
            val newChunk: Series<T> = 1 j { item }
            chunks = (chunks.size + 1) j { i ->
                if (i < chunks.size) chunks[i] else newChunk
            }
        }
        totalSize++
    }

    override fun add(index: Int, item: T) {
        if (index == totalSize) {
            add(item)
            return
        }
        val (ci, offset) = chunkIndexAndOffset(index)
        // Rebuild chunk: expand by 1, insert item at offset
        val oldChunk = chunks[ci]
        val newChunk: Series<T> = (oldChunk.size + 1) j { i ->
            when {
                i < offset -> oldChunk[i]
                i == offset -> item
                else -> oldChunk[i - 1]
            }
        }
        chunks = chunks.size j { i ->
            if (i == ci) newChunk else chunks[i]
        }
        totalSize++
    }

    override fun removeAt(index: Int): T {
        val (ci, offset) = chunkIndexAndOffset(index)
        val oldChunk = chunks[ci]
        val item = oldChunk[offset]
        if (oldChunk.size == 1) {
            // Remove the chunk entirely
            val nc = chunks.size - 1
            chunks = nc j { i ->
                if (i < ci) chunks[i] else chunks[i + 1]
            }
        } else {
            val newChunk: Series<T> = (oldChunk.size - 1) j { i ->
                if (i < offset) oldChunk[i] else oldChunk[i + 1]
            }
            chunks = chunks.size j { i ->
                if (i == ci) newChunk else chunks[i]
            }
        }
        totalSize--
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until totalSize) {
            if (b(i) == item) {
                removeAt(i)
                return true
            }
        }
        return false
    }

    override fun clear() {
        chunks = 0 j { throw IndexOutOfBoundsException("no chunks") }
        totalSize = 0
    }

    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { add(item) }
    override fun minusAssign(item: T) { remove(item) }
}
