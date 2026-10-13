package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.CausalityRete
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.ReteAssertion
import borg.trikeshed.narsese.KgTriplet
import borg.trikeshed.narsese.RuleNodes

/**
 * The node the JS version of this graph structurally could not have: an LCNC
 * node that runs [CausalityRete] IN-PROCESS. Not an HTTP call to a JVM
 * daemon, not a re-implementation of NARS evidence math in JavaScript — the
 * actual rete, the actual `EvidenceCoord` fixed-point arithmetic, called
 * directly from the graph interpreter because both now compile from the same
 * commonMain source.
 *
 * `nars.reteFire`: `rules` carries retained candidates, or params declare ONE
 * eternal rule (`antecedent`, `consequent`, `copula`); `discount` scales the
 * hypothetical firing's support. The `assertions` input is a list of
 * `{subject, obj}` maps (e.g. kanban facts: `{"subject":"card-1",
 * "obj":"blocked"}`). Output `firings` is the real, discounted
 * [borg.trikeshed.narsese.ReteFiring] result — never fabricated, never
 * routed through JSON-over-fetch. No live rule admission, bag write, or model
 * call occurs. Source candidates and hypothetical assumptions remain separate.
 */
object KanbanCausalNodes {

    fun runner(): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val discount = node.params["discount"]?.toFloatOrNull() ?: if (node.params["discount"] == null) 0.5f else Float.NaN
        require(discount.isFinite() && discount in 0f..1f) { "Firing discount must be in 0..1" }
        val wired = "rules" in inputs || "rules?" in inputs
        if (!wired) require(!node.params["antecedent"].isNullOrBlank() && !node.params["consequent"].isNullOrBlank()) {
            "nars.reteFire: rules input or antecedent and consequent params required"
        }
        // The existing single-rule node's discount acts at firing, not twice on the rule's evidence.
        val specs = RuleNodes.specifications(node.copy(params = node.params - "discount"), inputs)
        val decoded = Array(specs.size) { RuleNodes.rule(specs[it]) }
        val (rete, rejected) = CausalityRete.admit(decoded.size j decoded::get, discount)

        // Wires deliver values under the ?-suffixed port name as authored — dual-read.
        val raw = if ("assertions" in inputs) inputs["assertions"] else inputs["assertions?"]
        val rawAssertions = when (raw) {
            null -> emptyList()
            is List<*> -> raw
            is Array<*> -> raw.toList()
            else -> throw IllegalArgumentException("assertions must be an array")
        }
        val assertions: Series<ReteAssertion> = rawAssertions.size j { i ->
            val a = requireNotNull(rawAssertions[i] as? Map<*, *>) { "Each assertion must be an object" }
            val subject = requireNotNull(a["subject"] as? String) { "Assertion subject is required" }
                .also { require(it.isNotBlank()) { "Assertion subject is blank" } }
            val obj = a["obj"]?.let { requireNotNull(it as? String) { "Assertion obj must be a string" } } ?: ""
            val triplet = KgTriplet(subject, "state", obj)
            ReteAssertion(subject, obj, triplet.angularIdentity(), triplet.evidence(), RelationKind.CAUSALITY)
        }

        val firings = rete.fire(assertions)
        val out = (firings α { f ->
            mapOf(
                "antecedent" to f.rule.antecedent,
                "consequent" to f.rule.consequent,
                "matchedSubject" to f.matched.subject,
                "matchedObject" to f.matched.obj,
                "ruleCid" to f.sourceRuleCid.value,
                "provenanceCid" to f.rule.provenanceCid,
                "dependence" to f.dependence.name,
                "hypothetical" to true,
                "supportPositive" to f.support.positive,
                "supportNegative" to f.support.negative,
                "floored" to f.floored,
                "firingCid" to f.firingCid.value,
            )
        }).view.toList()
        mapOf("firings" to out, "ruleCid" to decoded.singleOrNull()?.ruleCid?.value,
            "ruleCids" to (rete.rules α { it.ruleCid.value }).view.toList(), "rejected" to rejected,
            "rules" to specs.view.mapIndexed { i, spec -> spec + mapOf("ruleCid" to decoded[i].ruleCid.value,
                "positiveEvidence" to decoded[i].evidence.positive, "negativeEvidence" to decoded[i].evidence.negative,
                "evaluated" to decoded[i].isEternal) },
            "assertions" to rawAssertions, "hypothetical" to true)
    }
}

/** The registry a kanban-shaped LCNC program can run against, in-process. */
fun kanbanLcncRegistry(): Map<String, LcncNodeRunner> = mapOf(
    "nars.reteFire" to KanbanCausalNodes.runner(),
)
