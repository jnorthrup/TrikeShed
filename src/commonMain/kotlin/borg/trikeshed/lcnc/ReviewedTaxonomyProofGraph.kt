package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.*

/** A bounded projection of a host-created reviewed read; validation cannot grant a ReviewPrincipal. */
object ReviewedTaxonomyProofGraph {
    const val SCHEMA = "reviewed-taxonomy-proof-quads/1"
    const val DATASET = "reviewed-taxonomy-proof-projection"
    const val VOCAB = "urn:trikeshed:reviewed-taxonomy-proof-quads:1:vocab:"
    private const val GRAPH = "urn:trikeshed:reviewed-taxonomy-proof-quads:1:"
    const val MAX_QUADS = 20_000
    const val MAX_CHARS = 4_000_000
    const val MAX_TERM = 4096

    class SourceProfileRefused(val code: String, val status: Int) : IllegalArgumentException(code)
    data class Projection(val store: QuadStore, val graph: String, val descriptor: Map<String, Any?>,
        val englishAvailable: Boolean) {
        val schema: String get() = SCHEMA
        val quadCount: Int get() = store.size
    }

    fun graphIri(readFrameCid: String): String = GRAPH + ContentId(readFrameCid).hex
    private fun checkedGraph(value: String): String = value.also {
        require(it.startsWith(GRAPH) && it == graphIri("sha256:" + it.removePrefix(GRAPH))) { "Invalid proof graph IRI" }
    }
    fun frameIri(graph: String): String = checkedGraph(graph) + "/frame"
    fun proofIri(graph: String, nodeCid: String): String = checkedGraph(graph) + "/proof/" + ContentId(nodeCid).hex
    fun sourceIri(graph: String, nodeCid: String): String = checkedGraph(graph) + "/source/" + ContentId(nodeCid).hex

    private fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: throw IllegalArgumentException("Proof record required")
    private fun text(value: Any?): String = value as? String ?: throw IllegalArgumentException("Proof text required")
    private fun rows(value: Any?, max: Int): List<Map<*, *>> = (value as? List<*>
        ?: throw IllegalArgumentException("Proof rows required")).also { require(it.size <= max) }.map(::map)
    private fun ordinal(value: Any?): Long {
        val n = (value as? Number)?.toDouble() ?: throw IllegalArgumentException("Proof ordinal required")
        require(n.isFinite() && n >= 0 && n <= 9_007_199_254_740_991.0 && n == n.toLong().toDouble())
        return n.toLong()
    }
    private fun limit() : Nothing = throw SourceProfileRefused("reviewed_taxonomy_source_profile_limit", 413)
    private fun raw(value: String) {
        if (value.length > MAX_TERM) limit()
        value.encodeToByteArray(throwOnInvalidSequence = true)
    }

    /** Local construction is never returned on a profile refusal; no truncated or partial dataset is published. */
    fun project(hostReport: Map<*, *>): Projection {
        if (hostReport["mappingStatus"] != DocumentTaxonomyCandidate.POLICY)
            throw SourceProfileRefused("reviewed_taxonomy_source_unavailable", 409)
        require(hostReport["kind"] == "reviewed-taxonomy-read/1")
        val plan = map(hostReport["plan"])
        require(plan["kind"] == ReviewedTaxonomyProofPaths.KIND)
        val sources = rows(plan["sources"], 4096)
        val proofs = rows(plan["proofs"], 1024)
        var uses = 0L
        for (proof in proofs) {
            val premises = proof["premises"] as? List<*> ?: throw IllegalArgumentException("Proof premises required")
            require(premises.size <= 4096)
            uses += premises.size
        }
        // A lower bound on distinct role-scoped quads refuses excessive full plans before receipt re-encoding.
        if (sources.size * 8L + proofs.size * 2L + uses * 4L > MAX_QUADS) limit()
        for (source in sources) { raw(text(source["text"])); raw(text(map(source["quotation"])["quote"])) }
        val origin = hostReport["englishOrigin"]?.let(::map)
        val witnesses = origin?.let { rows(it["witnesses"], 4096) }.orEmpty()
        for (witness in witnesses) raw(text(map(witness["englishQuotation"])["quote"]))
        ReviewedTaxonomyProofPaths.validate(plan)
        val goal = map(hostReport["goal"])
        require(goal.keys == _s["predicate", "subject", "object"] &&
            s_["predicate", "subject", "object"].view.all { goal[it] == plan[it] }) { "Proof goal differs" }
        require(hostReport["status"] == plan["status"] && hostReport["semantics"] == plan["semantics"] &&
            ordinal(hostReport["revision"]) == ordinal(plan["revision"]) &&
            sources.all { it["sourceRevision"] == hostReport["sourceRevision"] }) { "Proof frame metadata differs" }
        val frameFields = _m["kind" j "reviewed-kif-read-frame/1", "readCid" j text(hostReport["readCid"]),
            "page" j map(hostReport["page"]), "fragmentExportCid" j text(plan["exportCid"])] +
            if (origin == null) emptyMap() else _m["englishOrigin" j origin]
        require(ContentId.of(CanonicalCbor.encodeMap(frameFields)).value == hostReport["readFrameCid"]) { "Reviewed read frame identity differs" }
        val graph = graphIri(text(hostReport["readFrameCid"]))
        val store = QuadStore()
        var chars = 0L
        fun term(value: RdfTerm): String {
            when (value) {
                is RdfTerm.Literal -> raw(value.lexical)
                is RdfTerm.Iri -> raw(value.iri)
                else -> throw IllegalArgumentException("Only controlled IRIs and literals are projected")
            }
            val encoded = value.toTurtle()
            val lexeme = buildString {
                for (c in encoded) if (c.code < 32) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
            if (lexeme.length > MAX_TERM) limit()
            require(TurtleRdf.parseTerm(lexeme) == value) { "Proof RDF term cannot round-trip exactly" }
            return lexeme
        }
        val graphTerm = term(RdfTerm.Iri(graph))
        fun emit(subject: String, predicate: String, value: RdfTerm) {
            val s = term(RdfTerm.Iri(subject)); val p = term(RdfTerm.Iri(VOCAB + predicate)); val o = term(value)
            if (store.contains(s, p, o, graphTerm)) return
            val next = chars + graphTerm.length + s.length + p.length + o.length
            if (store.size >= MAX_QUADS || next > MAX_CHARS) limit()
            store.add(s, p, o, graphTerm); chars = next
        }
        fun literal(subject: String, predicate: String, value: Any?) = emit(subject, predicate, RdfTerm.Literal(text(value)))
        fun number(subject: String, predicate: String, value: Any?) = emit(subject, predicate,
            RdfTerm.Literal(ordinal(value).toString(), RdfVocab.XSD + "integer"))
        fun cid(subject: String, predicate: String, value: Any?) = emit(subject, predicate,
            RdfTerm.Iri("urn:sha256:" + ContentId(text(value)).hex))
        fun quotation(subject: String, kind: String, q: Map<*, *>) {
            val begin = ordinal(q["begin"]); val end = ordinal(q["end"]); val quote = text(q["quote"])
            require(end <= Int.MAX_VALUE && end > begin && end - begin == quote.length.toLong()) { "Proof quotation range differs" }
            literal(subject, "kind", kind); cid(subject, "originalCid", q["originalCid"])
            cid(subject, "extractedTextCid", q["extractedTextCid"])
            number(subject, "begin", begin); number(subject, "end", end)
            literal(subject, "unit", "utf16-code-unit-half-open"); literal(subject, "quote", quote)
        }
        val frame = frameIri(graph)
        literal(frame, "kind", "read-frame"); literal(frame, "schema", SCHEMA)
        for (key in s_["snapshotCid", "approvalCid", "decisionCid", "readCid", "readFrameCid", "curationReceiptCid", "ontologyCid"].view)
            cid(frame, key, hostReport[key])
        for ((key, field) in s_["planFrameId" j "frameId", "exportCid" j "exportCid", "queryId" j "queryId"].view) cid(frame, key, plan[field])
        number(frame, "revision", hostReport["revision"])
        literal(frame, "sourceRevision", hostReport["sourceRevision"])
        literal(frame, "status", plan["status"]); literal(frame, "semantics", plan["semantics"])
        emit(frame, "truncated", RdfTerm.Literal(plan["truncated"].toString(), RdfVocab.XSD + "boolean"))
        for (key in s_["predicate", "subject", "object"].view) literal(frame, "goal" + key.replaceFirstChar(Char::uppercaseChar), goal[key])
        val bySelection = sources.associateBy { text(it["selectionId"]) }
        val english = witnesses.associateBy { text(it["selectionId"]) }
        if (origin != null) {
            require(origin["authority"] == "explicit-reviewed-translation-assumptions-not-English-entailment" &&
                origin["independentDocumentsOrNarsEvidence"] == false && witnesses.isNotEmpty() && english.size == witnesses.size &&
                english.keys == bySelection.keys) { "English witness selection coverage differs" }
        }
        for (source in sources) {
            val node = sourceIri(graph, text(source["nodeId"]))
            emit(frame, "source", RdfTerm.Iri(node)); literal(node, "kind", "source-occurrence")
            for (key in s_["nodeId", "assertionCid", "sourceCid"].view) cid(node, key, source[key])
            literal(node, "selectionId", source["selectionId"]); literal(node, "sourceRevision", source["sourceRevision"])
            val q = map(source["quotation"])
            val kif = graph + "/quotation/kif/" + ContentId(text(source["nodeId"])).hex
            emit(node, "kifQuotation", RdfTerm.Iri(kif)); quotation(kif, "kif-quotation", q)
            val formula = KifSemantic.decode(text(source["text"])).formula as? KifFormula.Predicate
                ?: throw IllegalArgumentException("Ground taxonomy source required")
            require(formula.name in _s["instance", "subclass"] && formula.arguments.size == 2)
            val subject = (formula.arguments[0] as? KifTerm.Constant)?.token ?: throw IllegalArgumentException("KIF constant required")
            val obj = (formula.arguments[1] as? KifTerm.Constant)?.token ?: throw IllegalArgumentException("KIF constant required")
            literal(node, "predicate", formula.name); literal(node, "subjectToken", subject); literal(node, "objectToken", obj)
            literal(node, "subjectKind", "constant"); literal(node, "objectKind", "constant")
            literal(node, "subjectSort", if (formula.name == "instance") "individual" else "class"); literal(node, "objectSort", "class")
            english[text(source["selectionId"])]?.let { witness ->
                require(origin != null)
                for (key in s_["translationDecisionCid", "inspectionCid", "contextKey", "contextIri"].view)
                    require(witness[key] == origin[key]) { "English witness lineage differs" }
                val wq = map(witness["kifQuotation"])
                require(s_["originalCid", "extractedTextCid", "quote"].view.all { wq[it] == q[it] } &&
                    ordinal(wq["begin"]) == ordinal(q["begin"]) && ordinal(wq["end"]) == ordinal(q["end"])) { "English witness KIF occurrence differs" }
                val declared = map(witness["declared"])
                require(declared["relation"] == formula.name && declared["subjectIri"] == subject && declared["objectIri"] == obj &&
                    declared["subjectSort"] == if (formula.name == "instance") "individual" else "class") { "English declared terms differ" }
                require(declared["objectSort"] == "class")
                val e = graph + "/quotation/english/" + ContentId(text(source["nodeId"])).hex
                emit(node, "englishQuotation", RdfTerm.Iri(e)); quotation(e, "english-quotation", map(witness["englishQuotation"]))
                for (key in s_["translationDecisionCid", "inspectionCid", "contextKey"].view) cid(node, key, witness[key])
                cid(node, "declarationCid", witness["translationDecisionCid"]); cid(node, "derivedKifCid", q["extractedTextCid"])
                literal(node, "contextIri", witness["contextIri"]); number(e, "sentenceIndex", witness["sentenceIndex"])
            }
        }
        val byNode = sources.associateBy { text(it["nodeId"]) }
        for (proof in proofs) {
            val node = proofIri(graph, text(proof["nodeId"]))
            emit(frame, "proof", RdfTerm.Iri(node)); literal(node, "kind", "proof"); cid(node, "nodeId", proof["nodeId"])
            val premises = proof["premises"] as List<*>; val rules = proof["rules"] as List<*>
            for (index in premises.indices) {
                val source = byNode.getValue(text(premises[index]))
                val use = graph + "/use/" + ContentId(text(proof["nodeId"])).hex + "/" + index
                emit(node, "premiseUse", RdfTerm.Iri(use)); literal(use, "kind", "premise-use")
                emit(use, "source", RdfTerm.Iri(sourceIri(graph, text(source["nodeId"]))))
                number(use, "ordinal", index); literal(use, "rule", rules[index])
            }
        }
        val descriptor = linkedMapOf<String, Any?>("schema" to SCHEMA, "dataset" to DATASET, "graph" to graph,
            "quads" to store.size, "englishAvailable" to (origin != null), "persistence" to "request-local",
            "planFrameId" to plan["frameId"], "exportCid" to plan["exportCid"], "queryId" to plan["queryId"],
            "goal" to goal, "truncated" to plan["truncated"])
        for (key in s_["snapshotCid", "revision", "approvalCid", "decisionCid", "readCid", "readFrameCid", "sourceRevision", "status", "semantics"].view)
            descriptor[key] = hostReport[key]
        return Projection(store, graph, descriptor, origin != null)
    }
}
