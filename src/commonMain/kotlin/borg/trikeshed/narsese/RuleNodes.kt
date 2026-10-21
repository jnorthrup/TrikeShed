package borg.trikeshed.narsese

import borg.trikeshed.parse.reifyStrict

import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.boundLcnc
import borg.trikeshed.lib.*
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

    private fun copulaOf(raw: String?): NalCopula {
        val text = raw?.trim() ?: "==>"
        return requireNotNull(NalCopula.entries.firstOrNull { it.symbol == text || it.name == text }) {
            "Unknown rule copula: $text"
        }
    }

    /** Shared JSON boundary for hypothetical evaluation and explicit live admission. */
    fun specifications(node: LcncNode, inputs: Map<String, Any?>): Series<Map<*, *>> {
        val port = when { "rules" in inputs -> "rules"; "rules?" in inputs -> "rules?"; else -> null }
        if (port == null) return if (node.params["antecedent"].isNullOrBlank() || node.params["consequent"].isNullOrBlank())
            emptySeriesOf() else s_[node.params]
        val raw = inputs[port].let { if (it is String) reifyStrict(it) else it }
        val rows: Series<Any?> = when (raw) {
            is List<*> -> raw.toSeries()
            is Array<*> -> raw.size j { i: Int -> raw[i] }
            else -> throw IllegalArgumentException("rules must be an array")
        }
        return rows α { requireNotNull(it as? Map<*, *>) { "Each rule must be an object" } }
    }

    /** Explicit evidence retains both polarities. A pattern weight is not a confidence or a vote. */
    fun rule(spec: Map<*, *>): EternalRule {
        fun term(key: String) = requireNotNull(spec[key] as? String) { "Rule $key is required" }
            .also { require(it.isNotBlank()) { "Rule $key is blank" } }
        fun count(key: String): Long {
            val n = spec[key]?.toString()?.toDoubleOrNull()
            require(n != null && n.isFinite() && n in 0.0..4_294_967_295.0 && n % 1.0 == 0.0) {
                "$key must be an unsigned 32-bit evidence count"
            }
            return n.toLong()
        }
        val evidence = if ("positiveEvidence" in spec || "negativeEvidence" in spec) {
            require(spec["discount"] == null) { "Use explicit evidence or discount, not both" }
            EvidenceCoord(count("positiveEvidence"), count("negativeEvidence"))
        } else {
            val weight = spec["discount"]?.toString()?.toFloatOrNull() ?: if (spec["discount"] == null) 1f else Float.NaN
            require(weight.isFinite() && weight in 0f..1f) { "Rule discount must be in 0..1" }
            EvidenceCoord((Nal.UNIT * weight).toLong(), 0L)
        }
        val provenance = spec["provenanceCid"]?.let { requireNotNull(it as? String) { "provenanceCid must be a string" } }
        val result = EternalRule(term("antecedent"), term("consequent"), copulaOf(spec["copula"]?.toString()), evidence, provenance)
        require(spec["ruleCid"] == null || spec["ruleCid"] == result.ruleCid.value) {
            "ruleCid does not match rule terms, evidence and provenance"
        }
        return result
    }

    /**
     * `nal.rule.admit` — admit eternal rules into the live rete. The `rules`
     * input is a JSON list of `{antecedent, consequent, copula?, discount?}`
     * (reified maps from an upstream node, or JSON text); with no `rules`
     * input the node's own params declare ONE rule. Outputs the count the
     * swap actually added plus every offered rule's ruleCid.
     */
    fun ruleAdmitRunner(
        element: CausalityReteElement,
        review: (suspend (borg.trikeshed.job.ContentId) -> Map<String, Any?>)? = null,
        /**
         * Durability tee for admitted rules. The rete is in-memory and the daemon boots it over
         * zero rules, so without this every rule admitted through this node is gone at the next
         * restart. Called with each OFFERED rule — keying on `ruleCid` makes a re-admission of
         * an unchanged rule idempotent, so teeing the offered set rather than only the newly
         * added one costs nothing and avoids losing a rule that a concurrent admit raced us to.
         */
        ledger: ((EternalRule) -> Unit)? = null,
    ): LcncNodeRunner = boundLcnc(element) { element, node, inputs ->
        // Decode the whole offer before admission: a malformed later row cannot partially admit earlier rows.
        val release = inputs["releaseCid"] ?: inputs["releaseCid?"]
        val admittedReview = release?.let {
            checkNotNull(review) { "Document review authority is unavailable" }(borg.trikeshed.job.ContentId(it as String))
        }
        val specs = if (admittedReview != null) {
            val selected = admittedReview["ruleCandidates"] as List<*>
            require(selected.isNotEmpty()) { "Review release contains no rules" }
            selected.map { (it as Map<*, *>)["value"] as Map<*, *> }.toSeries()
        } else specifications(node, inputs).also { rows ->
            require(rows.view.none { "admission" in it || "quotation" in it || "sourceEvidence" in it || "snapshotCid" in it }) {
                "Document rule proposals require a current authenticated review release"
            }
        }
        val decoded = Array(specs.size) { rule(specs[it]) }
        val offered = (decoded.size j decoded::get).filter { it.isEternal }
        val admitted = if (offered.size == 0) 0 else element.admit(offered)
        // After the live admit, and never able to break it: a durability sink that is down
        // costs durability, not the rule that is already in the rete.
        if (ledger != null) for (r in offered.view) runCatching { ledger(r) }
        mapOf(
            "admitted" to admitted,
            "ruleCids" to (offered α { it.ruleCid.value }).view.toList(),
            "rejected" to decoded.size - offered.size,
            "review" to admittedReview?.filterKeys { it in setOf("releaseCid", "approvalCid", "snapshotCid", "admissionCid") },
        )
    }

    /**
     * `nal.rules.fromKg` — bridge interchange KR text to eternal rules and
     * admit them. A `copula` param forces every bridged rule's copula
     * (`==>` or `<=>`); without it [KgNalBridge.mapPredicate] decides, and
     * mapped temporal copulas are reported as `rejectedTemporal` — refused
     * at admission along with everything else non-eternal.
     */
    fun rulesFromKgRunner(element: CausalityReteElement): LcncNodeRunner = boundLcnc(element) { element, node, inputs ->
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
