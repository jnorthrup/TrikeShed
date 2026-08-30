package borg.trikeshed.narsese

import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport

/**
 * Rule-admission LCNC nodes — the seam that brings the daemon's LIVE
 * [CausalityReteElement] out of dead spin. The daemon boots the element over
 * zero rules, and a rete with an empty alpha network fires nothing forever;
 * these nodes are how eternal law reaches it after boot.
 *
 * `nal.rule.admit` admits explicit rules — a JSON list input of
 * `{antecedent, consequent, copula?, discount?}`, or ONE rule from the node's
 * own params. `nal.rules.fromKg` runs [KgNalBridge.bridgeToRules] over
 * interchange KR text (Turtle/KIF, sniffed) and admits the bridged rules —
 * temporal copulas are counted and refused at admission, never silently
 * reinterpreted.
 */
object RuleNodes {

    private fun copulaOf(raw: String?): NalCopula = when (raw?.trim()) {
        "<=>", "EQUIVALENCE" -> NalCopula.EQUIVALENCE
        else -> NalCopula.IMPLICATION
    }

    /** Per-rule discount scales the banked evidence weight (1.0 = one full observation). */
    private fun rule(antecedent: String, consequent: String, copula: String?, discount: Float?): EternalRule =
        EternalRule(
            antecedent = antecedent,
            consequent = consequent,
            copula = copulaOf(copula),
            evidence = EvidenceCoord((Nal.UNIT * (discount ?: 1f)).toLong(), 0L),
        )

    /**
     * `nal.rule.admit` — admit eternal rules into the live rete. The `rules`
     * input is a JSON list of `{antecedent, consequent, copula?, discount?}`
     * (reified maps from an upstream node, or JSON text); with no `rules`
     * input the node's own params declare ONE rule. Outputs the count the
     * swap actually added plus every offered rule's ruleCid.
     */
    fun ruleAdmitRunner(
        element: CausalityReteElement,
        /**
         * Durability tee for admitted rules. The rete is in-memory and the daemon boots it over
         * zero rules, so without this every rule admitted through this node is gone at the next
         * restart. Called with each OFFERED rule — keying on `ruleCid` makes a re-admission of
         * an unchanged rule idempotent, so teeing the offered set rather than only the newly
         * added one costs nothing and avoids losing a rule that a concurrent admit raced us to.
         */
        ledger: ((EternalRule) -> Unit)? = null,
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val specs: List<Map<*, *>> = when (val raw = inputs["rules"] ?: inputs["rules?"]) {
            is List<*> -> raw.mapNotNull { it as? Map<*, *> }
            is String -> (JsonSupport.parse(raw) as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
            else -> emptyList()
        }
        val offered: List<EternalRule> = if (specs.isNotEmpty()) specs.mapNotNull { spec ->
            val antecedent = spec["antecedent"]?.toString() ?: return@mapNotNull null
            val consequent = spec["consequent"]?.toString() ?: return@mapNotNull null
            rule(antecedent, consequent, spec["copula"]?.toString(), spec["discount"]?.toString()?.toFloatOrNull())
        } else {
            val antecedent = node.params["antecedent"]
            val consequent = node.params["consequent"]
            if (antecedent.isNullOrBlank() || consequent.isNullOrBlank()) emptyList()
            else listOf(rule(antecedent, consequent, node.params["copula"], node.params["discount"]?.toFloatOrNull()))
        }
        val admitted = if (offered.isEmpty()) 0 else element.admit(offered.toSeries())
        // After the live admit, and never able to break it: a durability sink that is down
        // costs durability, not the rule that is already in the rete.
        if (ledger != null) for (r in offered) runCatching { ledger(r) }
        mapOf(
            "admitted" to admitted,
            "ruleCids" to offered.map { it.ruleCid.value },
        )
    }

    /**
     * `nal.rules.fromKg` — bridge interchange KR text to eternal rules and
     * admit them. A `copula` param forces every bridged rule's copula
     * (`==>` or `<=>`); without it [KgNalBridge.mapPredicate] decides, and
     * mapped temporal copulas are reported as `rejectedTemporal` — refused
     * at admission along with everything else non-eternal.
     */
    fun rulesFromKgRunner(element: CausalityReteElement): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val kgText = (inputs["kgText"] ?: inputs["kgText?"])?.toString() ?: node.params["kgText"] ?: ""
        val confidence = node.params["confidence"]?.toFloatOrNull() ?: 0.9f
        val forced = node.params["copula"]?.takeIf { it.isNotBlank() }?.let(::copulaOf)
        val bridged = KgNalBridge.bridgeToRules(kgText, confidence)
        val offered = (0 until bridged.size).map { i ->
            val r = bridged[i]
            if (forced != null) r.copy(copula = forced) else r
        }
        val rejectedTemporal = offered.count { it.copula.isTemporal }
        val admitted = if (offered.isEmpty()) 0 else element.admit(offered.toSeries())
        mapOf(
            "rules" to offered.map {
                mapOf(
                    "antecedent" to it.antecedent,
                    "consequent" to it.consequent,
                    "copula" to it.copula.name,
                    "ruleCid" to it.ruleCid.value,
                )
            },
            "admitted" to admitted,
            "rejectedTemporal" to rejectedTemporal,
        )
    }
}
