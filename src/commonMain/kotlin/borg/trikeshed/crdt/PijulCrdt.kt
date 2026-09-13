package borg.trikeshed.crdt

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.patch.Blake3Hash
import borg.trikeshed.pijul.*

/**
 * Pijul CRDT — commutative patch graph over stable vertex identities.
 *
 * Every vertex keeps the coordinate space it was authored in: patch positions
 * resolve against the BASE document (the first applied patch's render), never
 * against the live rendered offsets. Concurrent patches therefore anchor to
 * the same base vertices regardless of apply order, and independent inserts
 * and deletes commute. Deletion tombstones content without removing the
 * vertex, so a patch aimed "after B" still lands next to B even when B is
 * already dead.
 *
 * Changes inside one patch resolve sequentially — the author saw their own
 * earlier changes. Inter-patch positions resolve against the base space.
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
     * tombstoning. Base coordinates stay stable because tombstoning never
     * shrinks a vertex.
     */
    private val vertexSpan = mutableMapOf<VertexId, Int>()

    /** For inserted vertices: the base vertex they anchored to and their patch id —
     *  the deterministic same-anchor ordering key. */
    private val insertAnchor = mutableMapOf<VertexId, VertexId>()
    private val insertPatchId = mutableMapOf<VertexId, Blake3Hash>()

    /** Linearized order of ALL vertices (dead included) in document order. */
    private val aliveOrder = mutableListOf<VertexId>()
    private val cumulativeLen = mutableListOf<Int>()

    /** O(1) position lookup into [aliveOrder]. */
    private val indexOf = mutableMapOf<VertexId, Int>()

    private var dirty = true

    /**
     * Base coordinate space: per-vertex start offset as of the end of the
     * first applied patch (the base document). Patch positions resolve here,
     * so concurrent patches anchor identically regardless of apply order.
     */
    private var baseAnchored = false
    private val baseOrder = mutableListOf<VertexId>()
    private val baseStart = mutableMapOf<VertexId, Int>()

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

                    var attachIdx = findAttachIndex(change.pos)
                    val attachVertex = aliveOrder[attachIdx]

                    // Forward edge: attachVertex → newVertex
                    childrenOf.getOrPut(attachVertex) { mutableListOf() }.add(newVertex)

                    // Insert into document order right after the attach vertex.
                    // Dead vertices remain in the order, so an anchor never
                    // moves and concurrent patches resolve identically.
                    //
                    // Concurrent inserts sharing one anchor order themselves by
                    // (patch id, authored offset): walking back past same-anchor
                    // inserts that sort AFTER the newcomer makes the group
                    // order independent of apply order.
                    var insertIdx = attachIdx + 1
                    while (true) {
                        val next = aliveOrder.getOrNull(insertIdx) ?: break
                        // Base vertices are boundaries, never same-anchor peers:
                        // only later concurrent inserts (no base presence) sort
                        // within the anchor's group.
                        if (baseStart.containsKey(next)) break
                        val nextAnchor = insertAnchor[next] ?: break
                        if (nextAnchor != attachVertex) break
                        if (compareKey(insertPatchId[next], next.offset) < compareKey(patch.id, newVertex.offset)) insertIdx++ else break
                    }
                    insertAnchor[newVertex] = attachVertex
                    insertPatchId[newVertex] = patch.id

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

        if (!baseAnchored) anchorBase()
    }

    /**
     * Snapshot the base coordinate space after the first (base) patch: every
     * vertex's start offset in the base document. All later patch positions
     * resolve against this space, which never shifts — inserts add new
     * vertices with no base presence, and deletes never remove vertices.
     */
    private fun anchorBase() {
        ensureCumulative()
        baseOrder.clear()
        baseStart.clear()
        var offset = 0
        for (v in aliveOrder) {
            baseOrder.add(v)
            baseStart[v] = offset
            offset += vertexSpan[v] ?: 0
        }
        baseAnchored = true
    }

    /**
     * Resolve a base-space position to the alive-order index of the last base
     * vertex whose base span starts at or before [pos]. Anchoring after that
     * vertex is stable: base spans never move, so two concurrent patches with
     * the same pos anchor to the same vertex in either apply order.
     */
    private fun baseAnchorIndex(pos: Int): Int {
        // Last vertex (in document order) whose base start is at or before pos.
        // Later-inserted vertices carry no base presence and never anchor.
        var best = 0
        for ((idx, v) in aliveOrder.withIndex()) {
            val start = baseStart[v] ?: continue
            if (start <= pos) best = idx
        }
        return best
    }


    /** Deterministic ordering key for same-anchor concurrent inserts. */
    private fun compareKey(id: Blake3Hash?, offset: Int): Key = Key(id?.bytes ?: ByteArray(32), offset)

    private data class Key(val bytes: ByteArray, val offset: Int) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val n = minOf(bytes.size, other.bytes.size)
            for (i in 0 until n) {
                val c = (bytes[i].toInt() and 0xFF).compareTo(other.bytes[i].toInt() and 0xFF)
                if (c != 0) return c
            }
            (bytes.size - other.bytes.size).let { if (it != 0) return it }
            return offset.compareTo(other.offset)
        }
    }

    /** Insertion index for a new vertex whose authored position is [pos]. */
    private fun findAttachIndex(pos: Int): Int {
        if (!baseAnchored) {
            // Base construction (first patch): resolve against current state.
            ensureCumulative()
            var lo = 0
            var hi = aliveOrder.lastIndex
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (cumulativeLen[mid] <= pos) lo = mid else hi = mid - 1
            }
            return lo
        }
        return baseAnchorIndex(pos)
    }

    /**
     * Base-space range [start, start+length) → alive-order index range of the
     * base vertices it covers. Resolves identically in any apply order.
     */
    private fun findRangeIndices(start: Int, length: Int): Pair<Int, Int> {
        if (!baseAnchored) {
            ensureCumulative()
            val end = start + length
            var lo = 0
            var hi = aliveOrder.lastIndex
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val vEnd = if (mid + 1 < aliveOrder.size) cumulativeLen[mid + 1] else cumulativeLen[mid] + contentLen(mid)
                if (vEnd <= start) lo = mid + 1 else hi = mid
            }
            val startIdx = lo
            hi = aliveOrder.lastIndex
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (cumulativeLen[mid] < end) lo = mid else hi = mid - 1
            }
            return startIdx to lo
        }
        val end = start + length
        var first = -1
        var last = -1
        for ((idx, v) in aliveOrder.withIndex()) {
            val vStart = baseStart[v] ?: continue
            val vEnd = vStart + (vertexSpan[v] ?: 0)
            if (vStart < end && start < vEnd) {
                if (first == -1) first = idx
                last = idx
            }
        }
        if (first == -1) {
            // Range matches no base vertex (empty or past-end): anchor at the nearest base vertex.
            val anchor = baseAnchorIndex(start)
            return anchor to anchor
        }
        return first to last
    }

    /**
     * The COORDINATE length of a vertex — its authored span, not what survives.
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
    /** Test/diagnostic view of the base coordinate space. */
    fun debugBaseStart(): Map<VertexId, Int> = baseStart.toMap()
    fun debugBaseAnchored(): Boolean = baseAnchored

    fun render(): String {
        ensureCumulative()
        val sb = StringBuilder()
        for (i in aliveOrder.indices) {
            sb.append(vertexContent[aliveOrder[i]] ?: "")
        }
        return sb.toString()
    }
}
