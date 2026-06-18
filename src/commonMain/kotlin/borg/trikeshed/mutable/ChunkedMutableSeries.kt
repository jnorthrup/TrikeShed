@file:Suppress("UNCHECKED_CAST")
package borg.trikeshed.mutable

import borg.trikeshed.lib.*

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
) : Appendable<T>, RandomAccess<T>, Insertable<T>, Removable<T> {

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

   var chunks: Series<Series<T>> = 0 j { throw IndexOutOfBoundsException("no chunks") }
   var totalSize: Int = 0

    // Stairs index for combine view

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

    private fun chunkIndexAndOffset(index: Int): Twin<Int> {
        val stairs = stairsArray()
        if (stairs.isEmpty()) throw IndexOutOfBoundsException("index $index, empty series")
        val ci = stairs.indexOfFirst { it > index }
        if (ci < 0) throw IndexOutOfBoundsException("index $index, total $totalSize")
        val offset = if (ci == 0) index else index - stairs[ci - 1]
        return ci j offset
    }

    // Series interface

    override val size: Int get() = totalSize
    override val a: Int get() = totalSize
    override val b: (Int) -> T = { i ->
        val (ci, offset) = chunkIndexAndOffset(i)
        chunks[ci][offset]
    }

    // MutableSeriesImpl

    override fun set(index: Int, item: T): MutableSeries<T> {
        val (ci, offset) = chunkIndexAndOffset(index)
        val oldChunk = chunks[ci]
        val newChunk: Series<T> = oldChunk.size j { i ->
            if (i == offset) item else oldChunk[i]
        }
        val oldChunks = chunks
        chunks = oldChunks.size j { i ->
            if (i == ci) newChunk else oldChunks[i]
        }
        return this
    }

    override fun append(item: T): MutableSeries<T> {
        if (totalSize == 0) {
            val firstChunk: Series<T> = 1 j { item }
            chunks = 1 j { firstChunk }
            totalSize = 1
            return this
        }
        val lastIdx = chunks.size - 1
        val lastChunk = chunks[lastIdx]
        if (lastChunk.size < chunkSize) {
            val newLast: Series<T> = (lastChunk.size + 1) j { i ->
                if (i < lastChunk.size) lastChunk[i] else item
            }
            val oldChunks = chunks
            chunks = oldChunks.size j { i ->
                if (i == lastIdx) newLast else oldChunks[i]
            }
        } else {
            val newChunk: Series<T> = 1 j { item }
            val oldChunks = chunks
            val oldLen = oldChunks.size
            chunks = (oldLen + 1) j { i ->
                if (i < oldLen) oldChunks[i] else newChunk
            }
        }
        totalSize++
        return this
    }

    override fun add(index: Int, item: T): MutableSeries<T> {
        if (index == totalSize) {
            add(item)
            return this
        }
        val (ci, offset) = chunkIndexAndOffset(index)
        val oldChunk = chunks[ci]
        val newChunk: Series<T> = (oldChunk.size + 1) j { i ->
            when {
                i < offset -> oldChunk[i]
                i == offset -> item
                else -> oldChunk[i - 1]
            }
        }
        val oldChunks = chunks
        chunks = oldChunks.size j { i ->
            if (i == ci) newChunk else oldChunks[i]
        }
        totalSize++
        return this
    }

    override fun removeAt(index: Int): Pair<MutableSeries<T>, T> {
        val (ci, offset) = chunkIndexAndOffset(index)
        val oldChunk = chunks[ci]
        val item = oldChunk[offset]
        if (oldChunk.size == 1) {
            val nc = chunks.size - 1
            val oldChunks = chunks
            chunks = nc j { i ->
                if (i < ci) oldChunks[i] else oldChunks[i + 1]
            }
        } else {
            val newChunk: Series<T> = (oldChunk.size - 1) j { i ->
                if (i < offset) oldChunk[i] else oldChunk[i + 1]
            }
            val oldChunks = chunks
            chunks = oldChunks.size j { i ->
                if (i == ci) newChunk else oldChunks[i]
            }
        }
        totalSize--
        return this to item
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

    override fun freeze(): MutableSeries<T> = this

    override fun cowUpdate(index: Int, item: T): MutableSeries<T> = set(index, item)

    override fun cowSnapshot(): MutableSeries<T> = this

    override fun insert(index: Int, item: T): MutableSeries<T> = add(index, item)

    override fun removeAt(index: Int): Pair<MutableSeries<T>, T> {
        val (ci, offset) = chunkIndexAndOffset(index)
        val oldChunk = chunks[ci]
        val item = oldChunk[offset]
        if (oldChunk.size == 1) {
            val nc = chunks.size - 1
            val oldChunks = chunks
            chunks = nc j { i ->
                if (i < ci) oldChunks[i] else oldChunks[i + 1]
            }
        } else {
            val newChunk: Series<T> = (oldChunk.size - 1) j { i ->
                if (i < offset) oldChunk[i] else oldChunk[i + 1]
            }
            val oldChunks = chunks
            chunks = oldChunks.size j { i ->
                if (i == ci) newChunk else oldChunks[i]
            }
        }
        totalSize--
        return this to item
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

    override fun concat(other: MutableSeries<T>): MutableSeries<T> {
        val otherChunks = other.sequence().toList().windowed(chunkSize, chunkSize, false) { chunk ->
            chunk.size j { i -> chunk[i] }
        }
        chunks = chunks.size j { i -> chunks[i] } + otherChunks.size j { i -> otherChunks[i] }
        totalSize += other.size
        return this
    }

    override fun sequence(): Sequence<T> = (0 until totalSize).asSequence().map { b(it) }

    override val size: Int get() = totalSize
    override val a: Int get() = totalSize
    override val b: (Int) -> T = { i ->
        val (ci, offset) = chunkIndexAndOffset(i)
        chunks[ci][offset]
    }
}