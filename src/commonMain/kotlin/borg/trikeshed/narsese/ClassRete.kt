package borg.trikeshed.narsese

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.j

/**
 * Rete alpha network over preorder class ids, as columns. Row r is one eternal implication:
 * `keys[r]` = packInts(antecedent class id, consequent term id), `evidence[r]` = [EvidenceCoord.packed].
 * Rows are sorted by key, so each antecedent owns one contiguous row range; each row's discounted
 * support is computed once, in one straight loop over the columns. A match is one Roaring AND of a
 * class's self+ancestor ids with [antecedents], then a gather of row ranges.
 */
class ClassRete(keyColumn: LongArray, evidenceColumn: LongArray, val discount: Float = 0.5f, val minSupport: Long = Nal.UNIT / 4) {
    private val keys: LongArray = keyColumn.copyOf().also { it.sort() }
    private val evidence: LongArray
    private val support: LongArray
    private val distinct: IntArray
    private val start: IntArray

    init {
        val order = keyColumn.indices.sortedBy { keyColumn[it] }
        evidence = LongArray(order.size) { evidenceColumn[order[it]] }
        val n = this.keys.size
        val ev = this.evidence
        support = LongArray(n)
        for (r in 0 until n) {
            val pos = ((ev[r] ushr 32) * discount).toLong()
            val neg = ((ev[r] and 0xFFFF_FFFFL) * discount).toLong()
            support[r] = if (pos < minSupport && neg == 0L) minSupport shl 32 else (pos shl 32) or neg
        }
        val d = IntAccumulator(n)
        val st = IntAccumulator(n + 1)
        for (r in 0 until n) if (r == 0 || antecedent(r) != antecedent(r - 1)) { d.add(antecedent(r)); st.add(r) }
        st.add(n)
        distinct = d.toIntArray()
        start = st.toIntArray()
    }

    /** Antecedent class id → its position in [distinct]. */
    private val index: FunnelHashIndex<Int> = FunnelHashIndex.build(distinct.size j { i: Int -> distinct[i] }, 0x434C_5254L)

    val antecedents: RoaringSeries = RoaringSeries.of(distinct)

    val size: Int get() = keys.size
    fun antecedent(row: Int): Int = (keys[row] ushr 32).toInt()
    fun consequent(row: Int): Int = keys[row].toInt()
    fun key(row: Int): Long = keys[row]
    fun evidence(row: Int): EvidenceCoord = EvidenceCoord(evidence[row])
    fun support(row: Int): EvidenceCoord = EvidenceCoord(support[row])

    /** Rows whose antecedent is in [selfAndAncestors], in antecedent order. */
    fun fire(selfAndAncestors: RoaringSeries): IntArray {
        val out = IntAccumulator(8)
        (selfAndAncestors and antecedents).forEach { id ->
            val k = index.get(id)!!
            for (r in start[k] until start[k + 1]) out.add(r)
        }
        return out.toIntArray()
    }
}
