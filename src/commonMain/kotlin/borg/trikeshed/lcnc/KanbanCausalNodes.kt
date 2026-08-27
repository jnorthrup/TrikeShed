package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.narsese.CausalityRete
import borg.trikeshed.narsese.EternalRule
import borg.trikeshed.narsese.EvidenceCoord
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.NalCopula
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.ReteAssertion
import borg.trikeshed.narsese.KgTriplet

/**
 * The node the JS version of this graph structurally could not have: an LCNC
 * node that runs [CausalityRete] IN-PROCESS. Not an HTTP call to a JVM
 * daemon, not a re-implementation of NARS evidence math in JavaScript — the
 * actual rete, the actual `EvidenceCoord` fixed-point arithmetic, called
 * directly from the graph interpreter because both now compile from the same
 * commonMain source.
 *
 * `nars.reteFire`: params declare ONE eternal rule (`antecedent`,
 * `consequent`, `copula`, `discount`); the `assertions` input is a list of
 * `{subject, obj}` maps (e.g. kanban facts: `{"subject":"card-1",
 * "obj":"blocked"}`). Output `firings` is the real, discounted
 * [borg.trikeshed.narsese.ReteFiring] result — never fabricated, never
 * routed through JSON-over-fetch.
 */
object KanbanCausalNodes {

    fun runner(): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val antecedent = node.params["antecedent"] ?: error("nars.reteFire: antecedent param required")
        val consequent = node.params["consequent"] ?: error("nars.reteFire: consequent param required")
        val copula = when (node.params["copula"] ?: "==>") {
            "<=>" -> NalCopula.EQUIVALENCE
            else -> NalCopula.IMPLICATION
        }
        val discount = node.params["discount"]?.toFloatOrNull() ?: 0.5f

        val rule = EternalRule(antecedent, consequent, copula, EvidenceCoord(Nal.UNIT, 0L))
        val rete = CausalityRete(1 j { rule }, discount = discount)

        @Suppress("UNCHECKED_CAST")
        val rawAssertions = inputs["assertions"] as? List<Map<String, Any?>> ?: emptyList()
        val assertions: Series<ReteAssertion> = rawAssertions.size j { i ->
            val a = rawAssertions[i]
            val subject = a["subject"]?.toString() ?: ""
            val obj = a["obj"]?.toString() ?: ""
            val triplet = KgTriplet(subject, "state", obj)
            ReteAssertion(subject, obj, triplet.angularIdentity(), triplet.evidence(), RelationKind.CAUSALITY)
        }

        val firings = rete.fire(assertions)
        val out = (0 until firings.size).map { i ->
            val f = firings[i]
            mapOf(
                "antecedent" to f.rule.antecedent,
                "consequent" to f.rule.consequent,
                "matchedSubject" to f.matched.subject,
                "supportPositive" to f.support.positive,
                "supportNegative" to f.support.negative,
                "floored" to f.floored,
                "firingCid" to f.firingCid.value,
            )
        }
        mapOf("firings" to out, "ruleCid" to rule.ruleCid.value)
    }
}

/** The registry a kanban-shaped LCNC program can run against, in-process. */
fun kanbanLcncRegistry(): Map<String, LcncNodeRunner> = mapOf(
    "nars.reteFire" to KanbanCausalNodes.runner(),
)
