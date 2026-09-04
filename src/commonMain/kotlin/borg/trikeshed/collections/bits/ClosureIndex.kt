package borg.trikeshed.collections.bits

/**
 * Transitive closure of a DAG as Roaring sets, numbered so the sets are cheap.
 *
 * Nodes are `0 until size` with a parent list each. The index assigns every
 * node a DFS-preorder id from the roots, so in a tree a node's descendants are
 * the single run `[id, id + subtreeSize)`, and a node with several parents
 * adds a few runs — the [RunContainer] shape. Ancestor sets are short and land
 * in [ArrayContainer]s. Both are computed once, bottom-up, and every query is
 * then a bit test: [isA] is `ancestors(sub).contains(id(sup))`.
 *
 * One builder for two callers — the SUMO classifier over subclass edges and
 * [borg.trikeshed.cursor.IsALattice] over IS-A edges — so the closure
 * algorithm exists once. Cycles are cut where met (a node reached again while
 * its own closure is being built contributes nothing to itself).
 */
class ClosureIndex private constructor(
    val size: Int,
    /** node → preorder id */
    private val idOf: IntArray,
    /** preorder id → node */
    private val nodeAt: IntArray,
    private val ancestors: Array<RoaringSeries>,
    private val descendants: Array<RoaringSeries>,
) {
    fun id(node: Int): Int = idOf[node]
    fun node(id: Int): Int = nodeAt[id]

    /** Proper ancestors of [node], as preorder ids. */
    fun ancestorIds(node: Int): RoaringSeries = ancestors[node]

    /** Proper descendants of [node], as preorder ids. */
    fun descendantIds(node: Int): RoaringSeries = descendants[node]

    /** Ancestors of [node] as nodes, nearest-id first (preorder id ascending = root-most first). */
    fun ancestorNodes(node: Int): IntArray = ancestors[node].toIntArray().also { for (i in it.indices) it[i] = nodeAt[it[i]] }

    fun descendantNodes(node: Int): IntArray = descendants[node].toIntArray().also { for (i in it.indices) it[i] = nodeAt[it[i]] }

    /** Reflexive: a node is-a itself. */
    fun isA(sub: Int, sup: Int): Boolean = sub == sup || ancestors[sub].contains(idOf[sup])

    /** `{id(node)} ∪ ancestorIds(node)` — the set a type constraint is checked against. */
    fun selfAndAncestorIds(node: Int): RoaringSeries = ancestors[node] or RoaringSeries.singleton(idOf[node])

    fun selfAndDescendantIds(node: Int): RoaringSeries = descendants[node] or RoaringSeries.singleton(idOf[node])

    /** Container shapes across every stored set — the proof that the numbering did its job. */
    fun shapeHistogram(): Map<String, Int> {
        val out = linkedMapOf("array" to 0, "run" to 0, "bitmap" to 0, "empty" to 0)
        for (sets in arrayOf(ancestors, descendants)) for (s in sets) {
            if (s.isEmpty()) { out["empty"] = out.getValue("empty") + 1; continue }
            for ((k, v) in s.shapeHistogram()) out[k] = out.getValue(k) + v
        }
        return out
    }

    fun byteSize(): Int = ancestors.sumOf { it.byteSize } + descendants.sumOf { it.byteSize }

    companion object {
        fun build(size: Int, parents: (Int) -> IntArray): ClosureIndex {
            val parentOf = Array(size) { parents(it) }
            val childCount = IntArray(size)
            for (n in 0 until size) for (p in parentOf[n]) if (p != n) childCount[p]++
            val children = Array(size) { IntArray(childCount[it]) }
            val fill = IntArray(size)
            for (n in 0 until size) for (p in parentOf[n]) if (p != n) children[p][fill[p]++] = n

            // DFS preorder from the roots; anything unreached (cycles) gets numbered after.
            val idOf = IntArray(size) { -1 }
            val nodeAt = IntArray(size)
            var next = 0
            val stack = IntAccumulatorStack()
            fun visit(root: Int) {
                if (idOf[root] >= 0) return
                stack.push(root)
                while (stack.isNotEmpty()) {
                    val v = stack.pop()
                    if (idOf[v] >= 0) continue
                    idOf[v] = next; nodeAt[next] = v; next++
                    val cs = children[v]
                    for (i in cs.indices.reversed()) if (idOf[cs[i]] < 0) stack.push(cs[i])
                }
            }
            for (n in 0 until size) if (parentOf[n].none { it != n }) visit(n)
            for (n in 0 until size) visit(n)

            val descendants = arrayOfNulls<RoaringSeries>(size)
            val ancestors = arrayOfNulls<RoaringSeries>(size)
            val state = ByteArray(size) // 0 new, 1 building, 2 done
            fun desc(v: Int): RoaringSeries {
                descendants[v]?.let { return it }
                if (state[v].toInt() == 1) return RoaringSeries.EMPTY
                state[v] = 1
                val acc = IntAccumulator()
                for (c in children[v]) { acc.add(idOf[c]); acc.addAll(desc(c)) }
                return acc.toRoaring().also { descendants[v] = it; state[v] = 2 }
            }
            for (n in 0 until size) desc(n)
            state.fill(0)
            fun anc(v: Int): RoaringSeries {
                ancestors[v]?.let { return it }
                if (state[v].toInt() == 1) return RoaringSeries.EMPTY
                state[v] = 1
                val acc = IntAccumulator()
                for (p in parentOf[v]) if (p != v) { acc.add(idOf[p]); acc.addAll(anc(p)) }
                return acc.toRoaring().also { ancestors[v] = it; state[v] = 2 }
            }
            for (n in 0 until size) anc(n)
            @Suppress("UNCHECKED_CAST")
            return ClosureIndex(size, idOf, nodeAt, ancestors as Array<RoaringSeries>, descendants as Array<RoaringSeries>)
        }
    }
}

private class IntAccumulatorStack {
    private var buf = IntArray(64)
    private var n = 0
    fun push(v: Int) { if (n == buf.size) buf = buf.copyOf(n * 2); buf[n++] = v }
    fun pop(): Int = buf[--n]
    fun isNotEmpty(): Boolean = n > 0
}
