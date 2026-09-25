package borg.trikeshed.narsese

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.TwInt
import borg.trikeshed.lib.j

/**
 * An eternal implication packed in one register: [key] is antecedent preorder class id
 * (of a [borg.trikeshed.collections.bits.ClosureIndex]) j consequent term id.
 */
data class ClassRule(val key: TwInt, val evidence: EvidenceCoord) {
    val antecedent: Int get() = key.first
    val consequent: Int get() = key.second
}

/**
 * Rete alpha network over preorder class ids. The admitted antecedents are one Roaring set;
 * a match against a class is one AND of that class's self+ancestor ids with it. Support is the
 * rule's evidence scaled by [discount], floored at [minSupport], as in [CausalityRete].
 */
class ClassRete(rules: Series<ClassRule>, val discount: Float = 0.5f, val minSupport: Long = Nal.UNIT / 4) {
    /** Rules sorted by antecedent; rules of the k-th distinct antecedent are `sorted[start[k] until start[k + 1]]`. */
    private val sorted: Array<ClassRule> = Array(rules.a) { rules.b(it) }.also { it.sortBy { r -> r.antecedent } }
    private val distinct: IntArray
    private val start: IntArray

    init {
        val d = IntAccumulator(sorted.size)
        val st = IntAccumulator(sorted.size + 1)
        for (i in sorted.indices) if (i == 0 || sorted[i].antecedent != sorted[i - 1].antecedent) { d.add(sorted[i].antecedent); st.add(i) }
        st.add(sorted.size)
        distinct = d.toIntArray()
        start = st.toIntArray()
    }

    /** Frozen antecedent class id → its position in [distinct]. */
    private val index: FunnelHashIndex<Int> = FunnelHashIndex.build(distinct.size j { i: Int -> distinct[i] }, 0x434C_5254L)

    val antecedents: RoaringSeries = RoaringSeries.of(distinct)

    /** Rules whose antecedent is in [selfAndAncestors], each joined to its discounted support. */
    fun fire(selfAndAncestors: RoaringSeries): Series<Join<ClassRule, EvidenceCoord>> {
        val out = ArrayList<Join<ClassRule, EvidenceCoord>>()
        (selfAndAncestors and antecedents).forEach { id ->
            val k = index.get(id)!!
            for (i in start[k] until start[k + 1]) {
                val rule = sorted[i]
                val pos = (rule.evidence.positive * discount).toLong()
                val neg = (rule.evidence.negative * discount).toLong()
                val support = if (pos < minSupport && neg == 0L) EvidenceCoord(minSupport, 0L) else EvidenceCoord(pos, neg)
                out.add(rule j support)
            }
        }
        return out.size j { i: Int -> out[i] }
    }
}
