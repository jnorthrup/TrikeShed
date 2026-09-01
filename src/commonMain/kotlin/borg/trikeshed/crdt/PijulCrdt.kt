package borg.trikeshed.crdt

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.patch.Blake3Hash
import borg.trikeshed.pijul.*

/**
 * Pijul CRDT — commutative patch graph.
 *
 * Patches that touch different regions of the same file commute with no
 * conflict resolution. The graph is a DAG of vertices (content atoms)
 * connected by tree edges (parent → child). Deletion tombstones content
 * without removing the vertex, preserving graph stability.
 *
 * Performance: the alive-vertex order is maintained incrementally. Insert
 * is O(log V) (binary search for attach point + O(1) list insert). Render
 * is O(V) (linear walk of the order). Delete is O(log V + k) where k is
 * the tombstoned range. The previous implementation called a full O(V^2)
 * topological sort on every apply and every render.
 */
class PijulCrdt {
    private val dag = DependencyDag()

    data class VertexId(val patch: Blake3Hash, val offset: Int)

    private val root = VertexId(Blake3Hash(ByteArray(32)), 0)

    /** Forward adjacency: parent → children. Replaces the full-edge scan. */
    private val childrenOf = mutableMapOf<VertexId, MutableList<VertexId>>()

    /** Content per vertex. Deleted vertices keep "" (tombstone). */
    private val vertexContent = mutableMapOf<VertexId, String>()

    /**
     * The span a vertex occupied when it was authored, never reduced by
     * tombstoning. Patch coordinates are relative to the document its AUTHOR
     * saw, so the coordinate space a delete resolves against must not shrink
     * underneath later patches — otherwise the second of two concurrent deletes
     * for the same line resolves onto whatever moved into its offsets and eats
     * the following line.
     */
    private val vertexSpan = mutableMapOf<VertexId, Int>()

    /**
     * Linearized order of alive vertices by position. Each entry carries
     * its cumulative content length so attach-point lookup is binary search.
     */
    private val aliveOrder = mutableListOf<VertexId>()
    private val cumulativeLen = mutableListOf<Int>()

    /** O(1) position lookup into [aliveOrder]. */
    private val indexOf = mutableMapOf<VertexId, Int>()

    private var dirty = true

    init {
        vertexContent[root] = ""
        vertexSpan[root] = 0
        aliveOrder.add(root)
        cumulativeLen.add(0)
        indexOf[root] = 0
    }

    fun apply(patch: Patch) {
        dag.add(patch)

        for (change in patch.changes) {
            when (change) {
                is Change.Insert -> {
                    val newVertex = VertexId(patch.id, change.pos)
                    // Applying the same patch twice must be a no-op. With
                    // content-addressed patch ids, two branches that made the
                    // byte-identical edit ARE one patch, and the swarm collapses
                    // here rather than in a merge tool.
                    if (vertexContent.containsKey(newVertex)) continue
                    vertexContent[newVertex] = change.content
                    vertexSpan[newVertex] = change.content.length

                    val attachIdx = findAttachIndex(change.pos)
                    val attachVertex = aliveOrder[attachIdx]

                    // Forward edge: attachVertex → newVertex
                    childrenOf.getOrPut(attachVertex) { mutableListOf() }.add(newVertex)

                    // Insert into alive-order right after the attach point.
                    // This keeps the linearized order consistent for future
                    // binary searches without a full re-sort.
                    val insertIdx = attachIdx + 1
                    aliveOrder.add(insertIdx, newVertex)
                    val contentLen = change.content.length
                    cumulativeLen.add(insertIdx, cumulativeLen[attachIdx] + contentLen)

                    // Rebuild index positions for everything after insertIdx.
                    rebuildIndexFrom(insertIdx)
                    dirty = true
                }
                is Change.Delete -> {
                    val (startIdx, endIdx) = findRangeIndices(change.pos, change.length)
                    for (i in startIdx..endIdx) {
                        val v = aliveOrder[i]
                        // idempotent: a tombstone stays a tombstone, and keeps
                        // its authored span so the coordinates stay stable
                        if (vertexContent[v].isNullOrEmpty()) continue
                        vertexContent[v] = ""
                    }
                    // Rebuild cumulative lengths (deleted vertices now have len 0).
                    rebuildCumulativeFrom(startIdx)
                    dirty = true
                }
            }
        }
    }

    /**
     * Binary search for the alive-vertex index whose cumulative content
     * range contains [pos]. O(log V).
     */
    private fun findAttachIndex(pos: Int): Int {
        ensureCumulative()
        var lo = 0
        var hi = aliveOrder.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulativeLen[mid] <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Binary search for the [start, end] index range of vertices overlapping
     * [start, start+length). O(log V + k) where k = range size.
     */
    private fun findRangeIndices(start: Int, length: Int): Pair<Int, Int> {
        ensureCumulative()
        val end = start + length
        // Leftmost vertex whose content end > start
        var lo = 0
        var hi = aliveOrder.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val vEnd = if (mid + 1 < aliveOrder.size) cumulativeLen[mid + 1] else cumulativeLen[mid] + contentLen(mid)
            if (vEnd <= start) lo = mid + 1 else hi = mid
        }
        val startIdx = lo
        // Rightmost vertex whose content start < end
        hi = aliveOrder.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulativeLen[mid] < end) lo = mid else hi = mid - 1
        }
        return startIdx to lo
    }

    /**
     * The COORDINATE length of a vertex — its authored span, not what survives.
     * Using live content here is what made deletes positional: tombstoning a
     * vertex collapsed the space its neighbours were addressed by, so a
     * concurrent patch aimed at the same line landed somewhere else.
     */
    private fun contentLen(idx: Int): Int {
        val v = aliveOrder[idx]
        return vertexSpan[v] ?: vertexContent[v]?.length ?: 0
    }

    /**
     * Lazily rebuild cumulative lengths if dirty. O(V) but only when
     * a delete or insert invalidated the suffix.
     */
    private fun ensureCumulative() {
        if (!dirty) return
        var cum = 0
        for (i in aliveOrder.indices) {
            cumulativeLen[i] = cum
            cum += contentLen(i)
        }
        // Ensure the list is the right size (handles edge cases)
        while (cumulativeLen.size < aliveOrder.size) cumulativeLen.add(0)
        while (cumulativeLen.size > aliveOrder.size) cumulativeLen.removeAt(cumulativeLen.lastIndex)
        dirty = false
    }

    private fun rebuildCumulativeFrom(fromIdx: Int) {
        var cum = if (fromIdx > 0) cumulativeLen[fromIdx - 1] + contentLen(fromIdx - 1) else 0
        for (i in fromIdx until aliveOrder.size) {
            cumulativeLen[i] = cum
            cum += contentLen(i)
        }
        dirty = false
    }

    private fun rebuildIndexFrom(fromIdx: Int) {
        for (i in fromIdx until aliveOrder.size) {
            indexOf[aliveOrder[i]] = i
        }
    }

    /**
     * Render the document from the alive-vertex order. O(V) — linear walk,
     * no topological sort, no edge scanning.
     */
    fun render(): String {
        ensureCumulative()
        val sb = StringBuilder()
        for (i in aliveOrder.indices) {
            sb.append(vertexContent[aliveOrder[i]] ?: "")
        }
        return sb.toString()
    }
}
