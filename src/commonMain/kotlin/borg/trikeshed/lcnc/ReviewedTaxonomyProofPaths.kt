package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*

/** A display projection of an existing proof receipt. Validation grants no review authority. */
object ReviewedTaxonomyProofPaths {
    const val KIND = "reviewed-taxonomy-proof-paths/1"

    /** Bind ALL projected sources to already authenticated frozen selection records, not a display page. */
    fun validate(value: Map<*, *>, selections: List<Map<*, *>>) {
        validate(value)
        require(selections.size <= 4096)
        val byId = selections.associateBy { it["id"] }
        val sources = (value["sources"] as List<*>).map { it as Map<*, *> }
        require(byId.size == selections.size && sources.size == selections.size &&
            sources.map { it["selectionId"] }.toSet() == byId.keys) { "Proof selection coverage differs" }
        for (source in sources) {
            val frozen = byId.getValue(source["selectionId"])["quotation"] as? Map<*, *> ?: error("Missing frozen quotation")
            val q = source["quotation"] as Map<*, *>
            require(s_["originalCid", "extractedTextCid", "begin", "end", "quote"].view.all { q[it] == frozen[it] }) {
                "Proof source differs from frozen quotation"
            }
        }
    }

    internal fun project(plan: KifActivationPlan, selections: Series<CurationReviewSelection>): Map<String, Any?> {
        val byId = selections.view.associateBy { it.id }
        require(byId.size == selections.size && plan.sources.size == selections.size) { "Proof source coverage differs" }
        val sources = plan.sources.view.map { entry ->
            val source = entry.source
            val item = requireNotNull(byId[source.id]) { "Proof source has no reviewed selection" }
            val q = item.quotation
            require(source.sourceCid == q.extractedTextCid && source.text == q.quote) { "Proof source quotation differs" }
            _m["nodeId" j entry.nodeId.value, "assertionCid" j entry.assertionCid.value,
                "selectionId" j source.id, "sourceCid" j source.sourceCid.value,
                "sourceRevision" j source.sourceRevision, "review" j source.review.name,
                "text" j source.text, "exclusion" j entry.exclusion,
                "quotation" j _m["originalCid" j q.originalCid.value,
                    "extractedTextCid" j q.extractedTextCid.value, "begin" j q.begin, "end" j q.end, "quote" j q.quote]]
        }
        val export = plan.export()
        return _m["kind" j KIND, "queryId" j plan.queryId.value, "frameId" j plan.frameId.value,
            "exportCid" j ContentId.of(export).value, "export" j export.decodeToString(),
            "revision" j plan.revision, "semantics" j plan.semantics,
            "predicate" j plan.predicate, "subject" j plan.subject, "object" j plan.obj,
            "status" j plan.status.name, "truncated" j plan.truncated, "steps" j plan.steps,
            "limits" j _m["maxAssertions" j plan.limits.maxAssertions, "maxTotalChars" j plan.limits.maxTotalChars,
                "maxSteps" j plan.limits.maxSteps, "maxProofs" j plan.limits.maxProofs],
            "sources" j sources, "proofs" j plan.proofs.view.map { proof ->
                _m["nodeId" j proof.nodeId.value, "premises" j proof.premises.view.map { it.value },
                    "rules" j proof.rules.view.toList()]
            }].also(::validate)
    }

    /**
     * Check display links against the canonical receipt, without evaluating any formula.
     * Callers must separately authenticate the host response and bind every source to their
     * frozen reviewed selection. A self-consistent guest receipt is not an approved assertion.
     */
    fun validate(value: Map<*, *>) {
        require(value["kind"] == KIND) { "Unknown proof projection" }
        fun map(raw: Any?) = raw as? Map<*, *> ?: error("Missing proof record")
        fun string(raw: Any?): String = raw as? String ?: error("Missing proof text")
        fun cid(raw: Any?) = ContentId(string(raw)).value
        fun integer(raw: Any?, maximum: Long): Long {
            val n = (raw as? Number)?.toDouble() ?: error("Missing proof ordinal")
            require(n.isFinite() && n >= 0 && n <= maximum.toDouble() && n == n.toLong().toDouble())
            return n.toLong()
        }
        fun rows(raw: Any?, maximum: Int): List<*> = (raw as? List<*> ?: error("Missing proof rows")).also {
            require(it.size <= maximum) { "Proof projection exceeds bound" }
        }
        val limits = map(value["limits"])
        val assertions = integer(limits["maxAssertions"], 4096).also { require(it > 0) }.toInt()
        val totalChars = integer(limits["maxTotalChars"], 4194304).also { require(it > 0) }
        val maxSteps = integer(limits["maxSteps"], 1000000).also { require(it > 0) }
        val maxProofs = integer(limits["maxProofs"], 1024).also { require(it > 0) }.toInt()
        val sources = rows(value["sources"], assertions).map(::map)
        val proofs = rows(value["proofs"], maxProofs).map(::map)
        require(sources.sumOf { string(it["text"]).length.toLong() } <= totalChars)
        val sourceIds = sources.map { cid(it["nodeId"]) }
        val selectionIds = sources.map { string(it["selectionId"]) }
        require(sourceIds.distinct().size == sources.size && selectionIds.distinct().size == sources.size) { "Duplicate proof source" }
        require(proofs.map { cid(it["nodeId"]) }.distinct().size == proofs.size) { "Duplicate proof" }
        require(value["semantics"] == "positive-ground-taxonomy-support/v1" &&
            value["status"] in _s["SUPPORTED", "UNKNOWN", "BOUNDED", "UNSUPPORTED"] && value["truncated"] is Boolean)
        require((value["status"] == "SUPPORTED") == proofs.isNotEmpty()) { "Proof status differs" }
        val export = string(value["export"])
        require(export.length <= 1048576) { "Proof receipt exceeds bound" }
        val bytes = export.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= 1048576 && ContentId.of(bytes).value == cid(value["exportCid"])) { "Proof receipt identity differs" }
        fun StringBuilder.fields(vararg fields: String) {
            for (field in fields) append(field.encodeToByteArray(throwOnInvalidSequence = true).size).append(':').append(field)
        }
        fun identity(vararg fields: String) = ContentId.of(buildString { fields(*fields) }
            .encodeToByteArray(throwOnInvalidSequence = true)).value
        require(value["queryId"] == identity("kif-query/v1", string(value["semantics"]),
            string(value["predicate"]), string(value["subject"]), string(value["object"]))) { "Proof query identity differs" }
        // Re-encode only receipt fields. Quotations additionally bind occurrence offsets below.
        val expected = buildString {
            fields("kif-activation-plan/v1", cid(value["queryId"]), cid(value["frameId"]),
                integer(value["revision"], 9007199254740991L).toString(),
                string(value["predicate"]), string(value["subject"]), string(value["object"]),
                string(value["semantics"]), string(value["status"]), value["truncated"].toString(),
                integer(value["steps"], maxSteps).toString(), assertions.toString(), totalChars.toString(),
                maxSteps.toString(), maxProofs.toString(), sources.size.toString())
            for (source in sources) {
                val q = map(source["quotation"])
                val begin = integer(q["begin"], Int.MAX_VALUE.toLong())
                val end = integer(q["end"], Int.MAX_VALUE.toLong())
                val text = string(source["text"])
                require(begin < end && end - begin == text.length.toLong() && source["selectionId"] == "kif:$begin:$end" &&
                    q["quote"] == text && q["extractedTextCid"] == source["sourceCid"] &&
                    source["review"] == "APPROVED" && source["exclusion"] == null) { "Proof occurrence differs" }
                cid(q["originalCid"])
                require(source["nodeId"] == identity("kif-source/v1", string(source["selectionId"]),
                    cid(source["sourceCid"]), string(source["sourceRevision"]), "APPROVED", text,
                    cid(source["assertionCid"]), "false", "")) { "Proof source identity differs" }
                fields(cid(source["nodeId"]), cid(source["assertionCid"]), string(source["selectionId"]),
                    cid(source["sourceCid"]), string(source["sourceRevision"]), "APPROVED", text, "false", "")
            }
            fields(proofs.size.toString())
            for (proof in proofs) {
                val premises = rows(proof["premises"], assertions)
                val rules = rows(proof["rules"], assertions)
                require(premises.isNotEmpty() && rules.size == premises.size && premises.all { it in sourceIds }) { "Dangling proof premise" }
                val proofBytes = buildString {
                    fields("kif-proof/v1", cid(value["queryId"]), premises.size.toString())
                    for (premise in premises) fields(cid(premise))
                    fields(rules.size.toString())
                    for (rule in rules) fields(string(rule))
                }.encodeToByteArray(throwOnInvalidSequence = true)
                require(proof["nodeId"] == ContentId.of(proofBytes).value) { "Proof node identity differs" }
                fields(cid(proof["nodeId"]), premises.size.toString())
                for (premise in premises) fields(cid(premise))
                fields(rules.size.toString())
                for (rule in rules) {
                    require(rule in _s["kif-taxonomy/v1/instance-assertion", "kif-taxonomy/v1/subclass-assertion",
                        "kif-taxonomy/v1/instance-subclass", "kif-taxonomy/v1/subclass-transitivity", "kif-taxonomy/v1/class-reflexivity"])
                    fields(string(rule))
                }
            }
        }
        require(expected == export) { "Proof projection differs from its receipt" }
        val frame = buildString {
            fields("kif-frame/v1", integer(value["revision"], 9007199254740991L).toString(),
                cid(value["queryId"]), string(value["semantics"]), assertions.toString(), totalChars.toString(),
                maxSteps.toString(), maxProofs.toString(), sources.size.toString())
            for (source in sources) fields(cid(source["nodeId"]))
            fields(string(value["status"]), value["truncated"].toString(), integer(value["steps"], maxSteps).toString(), proofs.size.toString())
            for (proof in proofs) fields(cid(proof["nodeId"]))
        }.encodeToByteArray(throwOnInvalidSequence = true)
        require(value["frameId"] == ContentId.of(frame).value) { "Proof frame identity differs" }
    }
}
