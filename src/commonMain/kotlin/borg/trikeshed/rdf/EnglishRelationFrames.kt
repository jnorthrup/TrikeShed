package borg.trikeshed.rdf

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*

/** Ordered parser roles in one verified inspection. No asserted propositions or evidence weights. */
object EnglishRelationFrames {
    const val KIND = "english-direct-relation-observations/1"
    const val PROFILE = "observed-active-nsubj-verb-obj/1"
    const val MAX_BYTES = 4 * 1024 * 1024
    private fun map(value: Any?): Map<*, *> {
        require(value is Map<*, *>) { "Relation observation map required" }
        return value
    }
    private fun rows(value: Any?) = value as? List<*> ?: error("Relation observation rows required")
    private fun integer(value: Any?) = (value as? Number)?.toInt() ?: error("Relation observation integer required")

    class Report(val snapshot: EnglishObservationGraph.Snapshot, val value: Map<String, Any?>,
        val frames: List<Map<String, Any?>>, val query: String, val results: Map<*, *>) {
        val cid: ContentId = ContentId.of(CanonicalCbor.encodeMap(value))
    }

    /** A missing query means all observations; explicit null and unknown fields are refused. */
    fun request(value: Any?): Map<String, String> {
        val raw = if (value is String) {
            require(value.encodeToByteArray(throwOnInvalidSequence = true).size <= 16_384) { "Relation query exceeds 16KiB" }
            var depth = 0; var quoted = false; var escaped = false
            for (ch in value) if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> require(++depth <= 1) { "Relation query must be flat" }
                '}', ']' -> require(--depth >= 0) { "Malformed relation query" }
            }
            require(depth == 0 && !quoted) { "Malformed relation query" }
            reifyStrict(value)
        } else value
        val fields = map(raw)
        require(fields.size <= 3 && fields.keys.all { it in _s["predicate", "subject", "object"] }) { "Only exact predicate, subject and object filters are supported" }
        return fields.entries.associate { entry ->
            val token = requireNotNull(entry.value as? String) { "Exact lexical relation filter required" }
            require(token.isNotBlank() && token.encodeToByteArray(throwOnInvalidSequence = true).size <= 4096) { "Relation filter token limit" }
            (entry.key as String j token).pair
        }
    }

    private val variables: List<String> get() = _l["sentence", "predicate", "subject", "object", "subjectEdge", "objectEdge"]

    private fun uri(binding: Map<*, *>, key: String): String {
        val term = map(binding[key])
        require(term.keys == _s["type", "value"] && term["type"] == "uri") { "Relation query term kind or fields differ" }
        return requireNotNull(term["value"] as? String) { "Relation query URI required" }
    }

    /** Full inventory join, including copied, ambiguous and otherwise excluded observations. */
    private fun expectedBindings(snapshot: EnglishObservationGraph.Snapshot): Set<List<String>> {
        val expected = mutableSetOf<List<String>>()
        for (raw in rows(snapshot.inventory["sentences"])) {
            val sentence = map(raw)
            val sentenceIri = snapshot.graph + "/sentence/${integer(sentence["index"])}"
            val edges = rows(sentence["dependencies"]).map(::map).withIndex().groupBy { it.value["governorNode"] }
            for ((head, outgoing) in edges) {
                val subjects = outgoing.filter { it.value["relation"] == "nsubj" }
                val objects = outgoing.filter { it.value["relation"] == "obj" }
                require(subjects.size.toLong() * objects.size <= 4096 - expected.size) { "Relation join exceeds 4096 observations" }
                for (subject in subjects) for (obj in objects) {
                    val tuple = _l[sentenceIri, "$sentenceIri/node/$head",
                        "$sentenceIri/node/${subject.value["dependentNode"]}", "$sentenceIri/node/${obj.value["dependentNode"]}",
                        "$sentenceIri/dependency/${subject.index}", "$sentenceIri/dependency/${obj.index}"]
                    require(expected.add(tuple)) { "Repeated expected relation tuple" }
                }
            }
        }
        return expected
    }

    /** Check every typed result before profile exclusions, preserving occurrence-specific edge identities. */
    internal fun validateBindings(snapshot: EnglishObservationGraph.Snapshot, results: Map<*, *>): List<Map<*, *>> {
        val expected = expectedBindings(snapshot)
        require(map(results["head"])["vars"] == variables) { "Relation query columns differ" }
        val bindings = rows(map(results["results"])["bindings"])
        require(bindings.size == expected.size) { "Relation quad join is incomplete or repeats observations" }
        val seen = mutableSetOf<List<String>>()
        val checked = bindings.map { raw ->
            val binding = map(raw)
            require(binding.keys == variables.toSet()) { "Relation query binding fields differ" }
            val tuple = variables.map { uri(binding, it) }
            require(tuple in expected && seen.add(tuple)) { "Relation query has an unknown or repeated exact tuple" }
            binding
        }
        require(seen == expected) { "Relation quad join omitted an expected exact tuple" }
        return checked
    }

    fun build(snapshot: EnglishObservationGraph.Snapshot): Report {
        require(!snapshot.legacyIdentity) { "Reported full dependency node identity required" }
        val inventory = snapshot.inventory
        val coverage = map(inventory["coverage"])
        require(coverage["parserNodeIdentityReported"] == true && coverage["parserDependencyNodeIdentity"] == "token-index+copy-count/1") { "Reported full dependency node identity required" }
        val range = map(inventory["range"])
        val legal = EnglishLegalPatterns.inspect(snapshot.source, snapshot.parsed, integer(range["begin"]), integer(range["end"]))
        val guards = map(legal["guardAttachments"])
        val guardSentences = rows(guards["sentences"]).map(::map).associateBy { integer(it["index"]) }
        expectedBindings(snapshot) // Bound the full raw join before the query executes.
        val store = EnglishObservationGraph.project(snapshot)
        fun p(local: String) = RdfTerm.Iri(EnglishObservationGraph.VOCAB + local).toTurtle()
        val graph = RdfTerm.Iri(snapshot.graph).toTurtle()
        val query = "SELECT ?sentence ?predicate ?subject ?object ?subjectEdge ?objectEdge WHERE { GRAPH $graph { " +
            "?subjectEdge ${p("relation")} \"nsubj\" . ?subjectEdge ${p("governor")} ?predicate . " +
            "?subjectEdge ${p("dependent")} ?subject . ?subjectEdge ${p("sentence")} ?sentence . " +
            "?objectEdge ${p("relation")} \"obj\" . ?objectEdge ${p("governor")} ?predicate . " +
            "?objectEdge ${p("dependent")} ?object . ?objectEdge ${p("sentence")} ?sentence . } }"
        val reply = SparqlSurface(store).answer(query, emptyList(), emptyList(), "json", "", selected = SparqlSource.STORE)
        require(reply.status == 200 && reply.body.encodeToByteArray().size <= MAX_BYTES) { "Relation quad query refused or bounded" }
        val results = map(reifyStrict(reply.body))
        val joined = validateBindings(snapshot, results)
        val frames = mutableListOf<Map<String, Any?>>()
        val excluded = mutableListOf<Map<String, Any?>>()
        for (raw in rows(inventory["sentences"])) {
            val sentence = map(raw); val index = integer(sentence["index"])
            val sentenceIri = snapshot.graph + "/sentence/$index"
            val nodes = rows(sentence["nodes"]).map(::map).associateBy { it["id"] }
            val edges = rows(sentence["dependencies"]).map(::map)
            val guard = guardSentences.getValue(index)
            val reasons = mutableListOf<String>()
            if (nodes.values.any { integer(it["copy"]) != 0 }) reasons.add("copied-nodes-outside-profile")
            if (edges.any { (it["relation"] as String).let { r -> r.startsWith("conj") || r == "cc" } }) reasons.add("coordination-outside-profile")
            if (edges.any { (it["relation"] as String).contains("pass") }) reasons.add("passive-outside-profile")
            if (rows(guard["quotationIndicatorNodeIds"]).isNotEmpty()) reasons.add("quotation-outside-profile")
            val candidates = joined.filter { uri(it, "sentence") == sentenceIri }
            if (candidates.isEmpty()) reasons.add("no-direct-nsubj-obj-join")
            val seen = mutableSetOf<String>()
            for (candidate in candidates) {
                val predicateIri = uri(candidate, "predicate")
                if (!seen.add(predicateIri)) continue
                val headId = predicateIri.removePrefix("$sentenceIri/node/")
                val head = nodes[headId] ?: error("Quad predicate differs from observed node")
                val subjects = edges.withIndex().filter { it.value["governorNode"] == headId && it.value["relation"] == "nsubj" }
                val objects = edges.withIndex().filter { it.value["governorNode"] == headId && it.value["relation"] == "obj" }
                val issues = reasons.toMutableList()
                if (subjects.size != 1 || objects.size != 1 || edges.any { it["governorNode"] == headId && it["relation"] in _s["dobj", "iobj", "csubj", "nsubj:pass"] })
                    issues.add("ambiguous-or-unsupported-role-bindings")
                if (!(head["pos"] as String).startsWith("VB")) issues.add("non-verb-governor")
                val related = candidates.filter { uri(it, "predicate") == predicateIri }
                require(related.size == subjects.size * objects.size) { "Relation quad join omitted or repeated bindings" }
                if (issues.isNotEmpty()) {
                    excluded.add(_m["sentenceIndex" j index, "predicateNodeIri" j predicateIri, "reasons" j issues.distinct(),
                        "dependencies" j sentence["dependencies"], "markers" j sentence["markers"], "guardObservations" j guard])
                    continue
                }
                val subject = nodes.getValue(subjects.single().value["dependentNode"])
                val obj = nodes.getValue(objects.single().value["dependentNode"])
                val subjectIri = "$sentenceIri/node/${subject["id"]}"; val objectIri = "$sentenceIri/node/${obj["id"]}"
                require(_s[predicateIri, subjectIri, objectIri].size == 3) { "Relation roles repeat a node" }
                val binding = related.single()
                require(uri(binding, "subject") == subjectIri && uri(binding, "object") == objectIri &&
                    uri(binding, "subjectEdge") == "$sentenceIri/dependency/${subjects.single().index}" &&
                    uri(binding, "objectEdge") == "$sentenceIri/dependency/${objects.single().index}") { "Relation quad bindings differ from exact observations" }
                val preimage = _m["kind" j "english-relation-occurrence/1", "inspectionCid" j snapshot.receiptCid.value,
                    "sentenceIri" j sentenceIri, "predicateNodeIri" j predicateIri, "subjectNodeIri" j subjectIri,
                    "objectNodeIri" j objectIri, "subjectEdgeIri" j uri(binding, "subjectEdge"), "objectEdgeIri" j uri(binding, "objectEdge")]
                frames.add(preimage + _m["occurrenceCid" j ContentId.of(CanonicalCbor.encodeMap(preimage)).value,
                    "sentenceIndex" j index, "begin" j sentence["begin"], "end" j sentence["end"], "quote" j sentence["quote"],
                    "predicate" j head["lemma"], "predicateWord" j head["word"], "subject" j subject["word"], "object" j obj["word"],
                    "bindings" j binding, "predicateNode" j head, "subjectNode" j subject, "objectNode" j obj,
                    "dependencies" j sentence["dependencies"], "markers" j sentence["markers"], "guardObservations" j guard,
                    "unresolved" j _l["role-observations-only", "entity-identity-not-established", "polarity-and-guard-scope-not-established", "legal-force-not-established"],
                    "basis" j _l[snapshot.source.originalCid.value, snapshot.source.extractedTextCid.value].distinct().sorted()))
            }
            if (candidates.isEmpty()) excluded.add(_m["sentenceIndex" j index, "reasons" j reasons.distinct(),
                "dependencies" j sentence["dependencies"], "markers" j sentence["markers"], "guardObservations" j guard])
        }
        val value = _m["kind" j KIND, "profile" j PROFILE, "inspectionCid" j snapshot.receiptCid.value,
            "namedGraph" j snapshot.graph, "quads" j store.size, "query" j query, "results" j results,
            "range" j range, "sentences" j inventory["sentences"], "frames" j frames, "excluded" j excluded,
            "status" j if (frames.isEmpty()) "NO_SUPPORTED_OBSERVATIONS" else "OBSERVED",
            "guardProjection" j "existing-EnglishLegalPatterns-over-recorded-inspection-not-parser-execution",
            "sourceBasis" j _l[snapshot.source.originalCid.value, snapshot.source.extractedTextCid.value].distinct().sorted(),
            "authority" j "recorded-parser-roles-only", "admission" j "not-performed", "countsAreEvidence" j false)
        require(CanonicalCbor.encodeMap(value).size <= MAX_BYTES) { "Relation report limit" }
        return Report(snapshot, value, frames, query, results)
    }

    /** Exact lexical filters of already checked quad bindings; no entity unification or inference. */
    fun query(report: Report, request: Map<String, String>): List<Map<String, Any?>> {
        val checked = this.request(request)
        return report.frames.filter { frame -> checked.all { (key, value) -> frame[key] == value } }
    }

    fun passages(report: Report, matches: List<Map<String, Any?>>): List<Map<String, Any?>> = matches.map { frame ->
        require(frame in report.frames) { "Unknown relation occurrence" }
        val quote = report.snapshot.source.quotation(integer(frame["begin"]), integer(frame["end"]))
        require(quote["quote"] == frame["quote"])
        quote + _m["inspectionCid" j report.snapshot.receiptCid.value, "frameCid" j report.cid.value,
            "occurrenceCid" j frame["occurrenceCid"], "subjectNodeIri" j frame["subjectNodeIri"],
            "predicateNodeIri" j frame["predicateNodeIri"], "objectNodeIri" j frame["objectNodeIri"],
            "basis" j frame["basis"], "meaning" j "one recorded role occurrence, not an independent evidence vote")
    }
}
