package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/** An eternal implication whose antecedent is a preorder class id of a [borg.trikeshed.collections.bits.ClosureIndex]. */
data class ClassRule(val antecedent: Int, val consequent: String, val evidence: EvidenceCoord)

/**
 * Rete alpha network over preorder class ids. The admitted antecedents are one Roaring set;
 * a match against a class is one AND of that class's self+ancestor ids with it. Support is the
 * rule's evidence scaled by [discount], floored at [minSupport], as in [CausalityRete].
 */
class ClassRete(rules: Series<ClassRule>, val discount: Float = 0.5f, val minSupport: Long = Nal.UNIT / 4) {
    private val byAntecedent: Map<Int, List<ClassRule>> =
        (0 until rules.a).map { rules.b(it) }.groupBy { it.antecedent }

    val antecedents: RoaringSeries = RoaringSeries.of(byAntecedent.keys)

    /** Rules whose antecedent is in [selfAndAncestors], each joined to its discounted support. */
    fun fire(selfAndAncestors: RoaringSeries): Series<Join<ClassRule, EvidenceCoord>> {
        val out = ArrayList<Join<ClassRule, EvidenceCoord>>()
        (selfAndAncestors and antecedents).forEach { id ->
            for (rule in byAntecedent.getValue(id)) {
                val pos = (rule.evidence.positive * discount).toLong()
                val neg = (rule.evidence.negative * discount).toLong()
                val support = if (pos < minSupport && neg == 0L) EvidenceCoord(minSupport, 0L) else EvidenceCoord(pos, neg)
                out.add(rule j support)
            }
        }
        return out.size j { i: Int -> out[i] }
    }
}
