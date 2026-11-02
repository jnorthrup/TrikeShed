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
 * `nal.rule.admit` requires an authenticated document review release unless the
 * host explicitly installs the legacy raw-rule policy. `nal.rules.fromKg` runs
 * [KgNalBridge.bridgeToRules] over interchange KR text (Turtle/KIF, sniffed)
 * and defaults to candidates only; its own legacy admission path is host opt-in.
 * Temporal copulas are counted and refused at admission, never silently reinterpreted.
 */
object RuleNodes {

    /** Host installation policy; graph input and node parameters never select it. */
    enum class AdmissionPolicy {
        REVIEW_RELEASE_REQUIRED,
        /** Explicit host opt-in for pre-existing experiments. Not document review. */
        TRUSTED_HOST_LEGACY,
    }

    /** Controls whether the KG bridge may mutate the live rete; set by the host only. */
    enum class KgAdmissionPolicy {
        CANDIDATES_ONLY,
        TRUSTED_HOST_LEGACY,
    }

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
     * `nal.rule.admit` requires a host-consumed review release by default. A release
     * supplies the exact selected rules; graph-provided rule rows are ignored.
     * Hosts explicitly opting into [AdmissionPolicy.TRUSTED_HOST_LEGACY] may also
     * admit raw manual rules; that option does not establish document provenance.
     *
     * The current review callback consumes before decoding/admission, and the optional
     * durability tee runs afterward. These operations are not atomic: a failed decode
     * may spend a release, and a failed tee may leave a live but non-durable rule.
     */
    fun ruleAdmitRunner(
        element: CausalityReteElement,
        review: (suspend (borg.trikeshed.job.ContentId) -> Map<String, Any?>)? = null,
        policy: AdmissionPolicy = AdmissionPolicy.REVIEW_RELEASE_REQUIRED,
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
        require(release != null || policy == AdmissionPolicy.TRUSTED_HOST_LEGACY) {
            "A host-issued review release is required for rule admission"
        }
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
     * `nal.rules.fromKg` — bridge interchange KR text to unreviewed candidates.
     * Only a host explicitly selecting [KgAdmissionPolicy.TRUSTED_HOST_LEGACY]
     * admits them to the live rete. A `copula` param forces every bridged rule's copula
     * (`==>` or `<=>`); without it [KgNalBridge.mapPredicate] decides, and
     * mapped temporal copulas are reported as `rejectedTemporal`; all
     * non-eternal copulas are also counted as `rejectedNonEternal`.
     */
    fun rulesFromKgRunner(element: CausalityReteElement,
        policy: KgAdmissionPolicy = KgAdmissionPolicy.CANDIDATES_ONLY): LcncNodeRunner = boundLcnc(element) { element, node, inputs ->
        val kgText = (inputs["kgText"] ?: inputs["kgText?"])?.toString() ?: node.params["kgText"] ?: ""
        val confidence = node.params["confidence"]?.toFloatOrNull() ?: 0.9f
        val forced = node.params["copula"]?.takeIf { it.isNotBlank() }?.let(::copulaOf)
        val bridged = KgNalBridge.bridgeToRules(kgText, confidence)
        val offered = (0 until bridged.size).map { i ->
            val r = bridged[i]
            if (forced != null) r.copy(copula = forced) else r
        }
        val rejectedTemporal = offered.count { it.copula.isTemporal }
        val admitted = if (policy == KgAdmissionPolicy.TRUSTED_HOST_LEGACY && offered.isNotEmpty())
            element.admit(offered.toSeries()) else 0
        mapOf<String, Any?>(
            "rules" to offered.map {
                mapOf<String, Any?>(
                    "antecedent" to it.antecedent,
                    "consequent" to it.consequent,
                    "copula" to it.copula.name,
                    "ruleCid" to it.ruleCid.value,
                ) + if (policy == KgAdmissionPolicy.CANDIDATES_ONLY) mapOf<String, Any?>(
                    "reviewStatus" to "unreviewed",
                    // Prevent direct handoff to the legacy raw-rule admission path;
                    // metadata can be stripped and is not an authority token.
                    "sourceEvidence" to mapOf("kind" to "unretained-kg-text"),
                ) else emptyMap<String, Any?>()
            },
            "admitted" to admitted,
            "admissionStatus" to if (policy == KgAdmissionPolicy.CANDIDATES_ONLY) "deferred-unreviewed" else "legacy-host-policy",
            "rejectedTemporal" to rejectedTemporal,
            "rejectedNonEternal" to offered.count { !it.isEternal },
        )
    }
}
