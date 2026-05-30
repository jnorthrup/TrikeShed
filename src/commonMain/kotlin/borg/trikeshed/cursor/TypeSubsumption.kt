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
// CowSeriesHandle for the mutable blackboard case or a plain Series
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
    fun supertypes(token: TypeToken): Series<TypeToken> {
        val visited = LinkedHashSet<TypeToken>()
        val queue = ArrayDeque<TypeToken>()
        queue.add(token)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (i in 0 until edges.size) {
                val e = edges[i]
                if (e.sub == cur && visited.add(e.sup)) queue.add(e.sup)
            }
        }
        val result = visited.toList()
        return result.size j { i: Int -> result[i] }
    }

    /**
     * Direct check: is [sub] a subtype of [sup] (transitively)?
     * Idempotent: pure function of the current edge set.
     */
    fun isA(sub: TypeToken, sup: TypeToken): Boolean {
        if (sub == sup) return true
        // BFS from sub
        val visited = HashSet<TypeToken>()
        val queue = ArrayDeque<TypeToken>()
        queue.add(sub)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (i in 0 until edges.size) {
                val e = edges[i]
                if (e.sub == cur) {
                    if (e.sup == sup) return true
                    if (visited.add(e.sup)) queue.add(e.sup)
                }
            }
        }
        return false
    }
}
