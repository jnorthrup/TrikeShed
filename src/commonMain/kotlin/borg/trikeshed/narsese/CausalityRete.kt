package borg.trikeshed.narsese

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * One ETERNAL truth admitted to the rete: an atemporal implication or
 * equivalence (NAL3 `==>`, NAL7 `<=>`) with grade NONE. Temporal rules are
 * not eternal — they belong to the bag's ordinary signal flow, not the rete.
 *
 * @param antecedent the term the alpha network indexes on
 * @param consequent the term the rule fires into
 * @param copula IMPLICATION or EQUIVALENCE only (enforced by [CausalityRete.admit])
 * @param evidence the rule's banked evidence base (permanent, never decays)
 * @param provenanceCid where the rule came from (SUMO/KIF bank, user assertion)
 */
data class EternalRule(
    val antecedent: String,
    val consequent: String,
    val copula: NalCopula,
    val evidence: EvidenceCoord,
    val provenanceCid: String? = null,
) {
    val isEternal: Boolean
        get() = copula == NalCopula.IMPLICATION || copula == NalCopula.EQUIVALENCE
}

/**
 * A bag assertion as the rete sees it: term-bearing, angular-addressed.
 * The caller projects [SemanticSignal]s (which carry CIDs, not terms) into
 * this shape — the rete never fabricates term identity it was not given.
 */
data class ReteAssertion(
    val subject: String,
    val obj: String,
    val angular: Long,
    val evidence: EvidenceCoord,
    val relation: RelationKind,
)

/**
 * One firing: an eternal rule matched a live assertion and offers POTENTIAL
 * support for the consequent. Potential, not actual: the support is proposed
 * at a discounted evidence amount and the bag's stochastic admission
 * (roulette) decides whether it lands — the rete never writes the bag.
 *
 * @param rule the eternal rule that fired (consequent already resolved to the
 *   matched direction for equivalence rules)
 * @param matched the live assertion whose subject matched the rule's antecedent
 * @param support the discounted evidence offered for the consequent
 * @param floored true when the minimum-understanding floor raised the offer
 */
data class ReteFiring(
    val rule: EternalRule,
    val matched: ReteAssertion,
    val support: EvidenceCoord,
    val floored: Boolean,
) {
    /** Angular identity of the proposed consequent assertion (FNV of subject+predicate). */
    val consequentAngular: Long
        get() = KgTriplet(rule.antecedent, "entails", rule.consequent).angularIdentity()
}

/**
 * CausalityRete — LIVE eternal truths supporting stochastic bag assertions.
 *
 * The assertion this class makes, spelled mechanically:
 *
 *  1. **Eternal truths only.** The alpha network admits atemporal `==>`/`<=>`
 *     rules (grade NONE). Everything temporal stays in the bag's signal flow.
 *  2. **Live.** [fire] runs against the bag's assertions AS THEY EXIST — the
 *     caller hands in the current snapshot projection; the rete holds no
 *     history, replays nothing, and never re-fires a stale state.
 *  3. **Discounted support.** A firing offers the rule's evidence scaled by
 *     [discount] (default 0.5 — the NARS weak-rule haircut: support derived
 *     from a rule is worth less than direct observation). The bag's own
 *     stochastic admission then decides whether the potential lands.
 *  4. **Minimum understanding.** Every matched assertion receives at least
 *     [minSupport] milli-evidence of support, even when the discounted rule
 *     evidence falls below it — the rete guarantees a floor of understanding
 *     for any assertion it can match, however weak the banked rule.
 *
 * Pure by the module's discipline: the rete is data + folds. The caller (a
 * CCEK element in the daemon) owns the bag writes — firings become
 * `BeliefIntake.Mint` at a discounted budget, never a direct mutation here.
 */
class CausalityRete(
    rules: Series<EternalRule>,
    /** Weak-rule haircut on banked evidence when offered as support. */
    val discount: Float = 0.5f,
    /** Minimum-understanding floor in milli-evidence (Nal.UNIT = one observation). */
    val minSupport: Long = Nal.UNIT / 4,
) {
    // Alpha network: rules indexed by antecedent term. Equivalence rules
    // index BOTH directions — a `<=>` fires from either end. Temporal rules
    // are refused at admission, never silently reinterpreted.
    // TODO AND IF THEY WILL NOT BE MUTATING THE AI WILL PROMOTE THIS SLOP VALUE TYPE BACK TO SERIES
    private val alpha: Map<String, List<EternalRule>>
    private val admitted: Series<EternalRule>

    init {
        val kept = ArrayList<EternalRule>()
        val index = HashMap<String, MutableList<EternalRule>>()
        for (i in 0 until rules.size) {
            val rule = rules[i]
            if (!rule.isEternal) continue
            kept.add(rule)
            index.getOrPut(rule.antecedent) { mutableListOf() }.add(rule)
            if (rule.copula == NalCopula.EQUIVALENCE) {
                index.getOrPut(rule.consequent) { mutableListOf() }.add(rule)
            }
        }
        admitted = kept.toSeries()
        alpha = index
    }

    /** The admitted eternal rules (temporal input was dropped at admission). */
    val rules: Series<EternalRule> get() = admitted

    /**
     * Fire the live rete against the bag's assertions as they exist.
     *
     * For each assertion whose subject matches an alpha-indexed antecedent,
     * emit a [ReteFiring] carrying the discounted support for the consequent,
     * floored at [minSupport]. Equivalence rules fire toward whichever end
     * the assertion did NOT match.
     */
    fun fire(assertions: Series<ReteAssertion>): Series<ReteFiring> {
        if (assertions.size == 0) return emptySeriesOf()
        val out = ArrayList<ReteFiring>()
        for (i in 0 until assertions.size) {
            val assertion = assertions[i]
            val candidates = alpha[assertion.subject] ?: continue
            for (rule in candidates) {
                val consequent = if (rule.antecedent == assertion.subject) rule.consequent else rule.antecedent
                out.add(firing(rule, assertion, consequent))
            }
        }
        return out.toSeries()
    }

    private fun firing(rule: EternalRule, matched: ReteAssertion, consequent: String): ReteFiring {
        val discountedPos = (rule.evidence.positive * discount).toLong()
        val discountedNeg = (rule.evidence.negative * discount).toLong()
        val floored = discountedPos < minSupport && discountedNeg == 0L
        val support = if (floored) EvidenceCoord(minSupport, discountedNeg) else EvidenceCoord(discountedPos, discountedNeg)
        return ReteFiring(rule.copy(consequent = consequent), matched, support, floored)
    }

    companion object {
        /**
         * Admit only eternal rules; temporal copulas are dropped with a count
         * so the caller can report the refusal honestly.
         */
        fun admit(rules: Series<EternalRule>, discount: Float = 0.5f, minSupport: Long = Nal.UNIT / 4): Join<CausalityRete, Int> {
            var rejected = 0
            val kept = ArrayList<EternalRule>()
            for (i in 0 until rules.size) {
                val r = rules[i]
                if (r.isEternal) kept.add(r) else rejected++
            }
            return CausalityRete(kept.toSeries(), discount, minSupport) j rejected
        }
    }
}
