@file:Suppress("INLINE_CLASS_DEPRECATED")

package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── Type Subsumption Staircase ───────────────────────────────────
//
// Inline value classes for zero-boxing IS-A relation algebra.
// Each level in the type hierarchy is a typed staircase step:
//
//   TypeToken         — a pool index identifying a type
//   IsAEdge           — a directed IS-A edge (sub → sup) packed as TwInt
//
// The staircase property: TypeToken(sub).edgeTo(TypeToken(sup)) is the
// bottom step; composition of edges is the transitive IS-A chain.
//
// All three classes use the same bit-packing pattern as TwInt / BudgetCoord
// in DenseTwins.kt — zero allocation on JVM for the common case.

/** TypeToken — identifies a type by its constant pool index.  Zero allocation. */
inline class TypeToken(val poolIdx: Int) {
    /** Build a directed IS-A edge from this type (sub) to [sup]. */
    infix fun edgeTo(sup: TypeToken): IsAEdge = IsAEdge(poolIdx, sup.poolIdx)

    override fun toString(): String = "TypeToken($poolIdx)"
}

/**
 * IsAEdge — a directed IS-A relationship packed as two Ints in one Long.
 *
 *   bits 32..63  =  sub  (the more-specific type)
 *   bits  0..31  =  sup  (the less-specific / supertype)
 *
 * Same layout as TwInt: (sub shl 32) or (sup and 0xFFFFFFFFL).
 */
inline class IsAEdge(val raw: Long) {
    constructor(sub: Int, sup: Int) :
        this((sub.toLong() shl 32) or (sup.toLong() and 0xFFFFFFFFL))

    val sub: TypeToken get() = TypeToken((raw ushr 32).toInt())
    val sup: TypeToken get() = TypeToken(raw.toInt())

    operator fun component1(): TypeToken = sub
    operator fun component2(): TypeToken = sup

    override fun toString(): String = "${sub} IS-A ${sup}"
}

// ── IsALattice — query algebra over a Series<IsAEdge> ────────────
//
// An IsALattice wraps an immutable or mutable Series<IsAEdge> and
// provides direct-child lookup and transitive closure queries.
// The backing store is deliberately opaque — callers can use a
// COWArrayBackend for the mutable blackboard case or a plain Series
// for the read-only CBOR-derived case.

class IsALattice(val edges: Series<IsAEdge>) {

    /** Direct supertypes of [token] — single hop. */
    fun directSupers(token: TypeToken): Series<TypeToken> {
        val buf = ArrayList<TypeToken>()
        for (i in 0 until edges.size)
            if (edges[i].sub == token) buf.add(edges[i].sup)
        return buf.size j { i: Int -> buf[i] }
    }

    /** Direct subtypes of [token] — single hop. */
    fun directSubs(token: TypeToken): Series<TypeToken> {
        val buf = ArrayList<TypeToken>()
        for (i in 0 until edges.size)
            if (edges[i].sup == token) buf.add(edges[i].sub)
        return buf.size j { i: Int -> buf[i] }
    }

    /**
     * LSP projection: transitive supertype chain of [token].
     *
     * Returns all types S such that token IS-A S (directly or transitively),
     * in BFS order (shallowest first).  This is the "staircase" read —
     * each step climbs one level in the IS-A hierarchy.
     */
    fun supertypes(token: TypeToken, maxDepth: Int = Int.MAX_VALUE): Series<TypeToken> {
        // Seed rides in visited: an edge cycling back to the seed (possible once
        // prose-mined addLinkCheck edges enter the lattice) must neither pollute
        // the result nor re-enqueue an already-walked frontier.
        val visited = LinkedHashSet<TypeToken>()
        visited.add(token)
        var frontier = ArrayDeque<TypeToken>().apply { add(token) }
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = ArrayDeque<TypeToken>()
            while (frontier.isNotEmpty()) {
                val cur = frontier.removeFirst()
                for (i in 0 until edges.size) {
                    val e = edges[i]
                    if (e.sub == cur && visited.add(e.sup)) next.add(e.sup)
                }
            }
            frontier = next
            depth++
        }
        val result = visited.drop(1) // seed is a guard, not a supertype of itself
        return result.size j { i: Int -> result[i] }
    }

    /**
     * Direct check: is [sub] a subtype of [sup] (transitively)?
     * Idempotent: pure function of the current edge set.
     */
    fun isA(sub: TypeToken, sup: TypeToken): Boolean {
        if (sub == sup) return true
        val idx = closure()
        val a = idx.dense[sub.poolIdx] ?: return false
        val b = idx.dense[sup.poolIdx] ?: return false
        return idx.index.isA(a, b)
    }

    /**
     * Delta (2026-09-04): [isA] is a bit test on a
     * [borg.trikeshed.collections.bits.ClosureIndex] built from the edge Series
     * (DFS-numbered Roaring ancestor sets) instead of a per-query BFS that
     * scanned every edge per hop. The index is rebuilt when the edge count
     * changes — the COW/mutable-backend case — and is otherwise a pure function
     * of the edge set, so [isA] stays idempotent. [supertypes] keeps its BFS
     * order (shallowest first), which the closure does not carry.
     */
    private class Closure(val edgeCount: Int, val dense: Map<Int, Int>, val index: borg.trikeshed.collections.bits.ClosureIndex)

    private var closureCache: Closure? = null

    private fun closure(): Closure {
        val n = edges.size
        closureCache?.let { if (it.edgeCount == n) return it }
        val dense = LinkedHashMap<Int, Int>()
        fun id(p: Int) = dense.getOrPut(p) { dense.size }
        val parents = ArrayList<ArrayList<Int>>()
        for (i in 0 until n) {
            val e = edges[i]
            val a = id(e.sub.poolIdx); val b = id(e.sup.poolIdx)
            while (parents.size < dense.size) parents.add(ArrayList())
            parents[a].add(b)
        }
        val index = borg.trikeshed.collections.bits.ClosureIndex.build(dense.size) { v -> parents[v].toIntArray() }
        return Closure(n, dense, index).also { closureCache = it }
    }
}
