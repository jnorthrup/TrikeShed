@file:Suppress("FunctionName", "NOTHING_TO_INLINE")

package borg.trikeshed.patl

import borg.trikeshed.lib.*

/**
 * Dense patricia trie node storage: each node is two [TwInt] values
 * packed as Longs in parallel [LongSeries] arrays.
 *
 * ```
 * links[i] = packInts(leftChild, rightChild)   →  Long → TwInt
 * meta[i]  = packInts(parentid, skip)          →  Long → TwInt
 * ```
 *
 * 16 bytes per node (2 × Long), zero boxing, algebraic composition via [TwInt].
 * LSB of parentid encodes child-id (0=left, 1=right).
 */
class IntNodeStore(
    /** links: TwInt(leftChild, rightChild) packed as Long per node */
    val links: LongSeries = LongSeries(0),
    /** meta: TwInt(parentid, skip) packed as Long per node */
    val meta: LongSeries = LongSeries(0),
) {
    val size: Int get() = links.a

    /** Append a new node. Returns its index. */
    fun append(
        parent: Int,
        parentId: Int,   // 0 = left, 1 = right
        leftChild: Int,
        rightChild: Int,
        skip: Int,
    ): Int {
        val idx = size
        links.add(packInts(leftChild, rightChild))
        meta.add(packInts(parent or parentId, skip))
        return idx
    }

    /** Initialize root node. Returns index 0. */
    fun initRoot(): Int = append(
        parent = NULL,
        parentId = 0,
        leftChild = NULL,
        rightChild = NULL,
        skip = 0,
    )

    // ── Inline hot-path accessors — extract packed TwInt fields ─────────

    /** Parent index with LSB stripped. */
    inline fun getParent(index: Int): Int = TwInt(meta[index]).a and PARENT_MASK

    /** Raw parent field (includes LSB = child id). */
    inline fun getParentRaw(index: Int): Int = TwInt(meta[index]).a

    inline fun getLeftChild(index: Int): Int = TwInt(links[index]).a
    inline fun getRightChild(index: Int): Int = TwInt(links[index]).b

    /** Dispatch child by id: 0 → left, 1 → right. */
    inline fun getChild(index: Int, id: Int): Int =
        if (id == 0) getLeftChild(index) else getRightChild(index)

    /** Set left child link. */
    inline fun setLeftChild(index: Int, child: Int) {
        val cur = TwInt(links[index])
        links[index] = packInts(child, cur.b)
    }

    /** Set right child link. */
    inline fun setRightChild(index: Int, child: Int) {
        val cur = TwInt(links[index])
        links[index] = packInts(cur.a, child)
    }

    inline fun getSkip(index: Int): Int = TwInt(meta[index]).b

    companion object {
        /** Sentinel: no node / null pointer. Must be even for PARENT_MASK round-trip. */
        const val NULL: Int = -2
        /** Mask to strip LSB from parentid. */
        const val PARENT_MASK: Int = -2
    }
}
