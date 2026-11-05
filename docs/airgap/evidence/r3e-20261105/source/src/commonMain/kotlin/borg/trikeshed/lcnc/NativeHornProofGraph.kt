package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.*

/** Projection/link consistency only. A caller-supplied map cannot establish native execution or review authority. */
object NativeHornProofGraph {
    const val SCHEMA = "native-horn-proof-quads/1"
    const val DATASET = "reviewed-native-horn-proof-projection"
    const val VOCAB = "urn:trikeshed:native-horn-proof-quads:1:vocab:"
    private const val PREFIX = "urn:trikeshed:native-horn-proof-quads:1:"
    const val MAX_QUADS = 20_000
    const val MAX_CHARS = 4_000_000
    const val MAX_TERM = 4096
    class ProfileRefused : IllegalArgumentException("Complete native proof exceeds the optional visual source profile")
    class Projection(val store: QuadStore, val graph: String, val projectionCid: ContentId, val descriptor: Map<String, Any?>)

    internal fun map(value: Any?) = value as? Map<*, *> ?: error("Native proof record required")
    internal fun text(value: Any?) = value as? String ?: error("Native proof text required")
    internal fun rows(value: Any?, limit: Int = 4096): List<*> = (value as? List<*> ?: error("Native proof rows required")).also {
        require(it.size <= limit) { "Native proof row bound" }
    }
    internal fun integer(value: Any?): Int {
        val n = (value as? Number)?.toDouble() ?: error("Native proof integer required")
        require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n % 1.0 == 0.0)
        return n.toInt()
    }
    fun graphIri(cid: String) = PREFIX + ContentId(cid).hex
    private fun checkedGraph(value: String) = value.also { require(it.startsWith(PREFIX) && it == graphIri("sha256:" + it.removePrefix(PREFIX))) }
    fun stepIri(graph: String, path: List<Int>): String {
        require(path.size <= 64 && path.all { it in 0..15 }) { "Native step path differs" }
        return checkedGraph(graph) + "/step/" + if (path.isEmpty()) "root" else path.joinToString(".")
    }
    fun sourceIri(graph: String, cid: String) = checkedGraph(graph) + "/source/" + ContentId(cid).hex
    internal fun path(value: Any?) = rows(value, 64).map(::integer).also { require(it.all { n -> n <= 15 }) }
    private fun cid(value: Any?) = ContentId(text(value)).value
    private fun normalized(value: Any?): Any? = when (value) {
        is Number -> value.toDouble().let { require(it.isFinite() && it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0); it.toLong() }
        is Map<*, *> -> value.entries.associate { (text(it.key) j normalized(it.value)).pair }
        is List<*> -> value.map(::normalized)
        else -> value
    }
    private fun encode(value: Map<*, *>) = CanonicalCbor.encodeMap(value.entries.associate { (text(it.key) j normalized(it.value)).pair })

    /** Entire immutable selection/context is hashed; volatile asOf is deliberately not a graph identity. */
    fun project(report: Map<*, *>): Projection {
        require(report["kind"] == "reviewed-kif-native-result/1" && report["scope"] == "host-explicit-assertions" &&
            report["policy"] == ReviewedKifHornPlan.POLICY && report["firstProofOnly"] == true && report["ontology"] == null &&
            report["coverage"] == "positive-reviewed-selected-snapshot" && integer(report["selectionLimit"]) == ReviewedKifHornPlan.MAX_SELECTIONS &&
            report["semantics"] == "positive-scalar-horn-first-proof/1" && report["authority"] == "reviewed-read-only-not-a-sink-permit") {
            "Reviewed native taxonomy result required"
        }
        require(report["sumoNixRevision"] == SumoNixBridge.SUMO_REVISION)
        require(report["reviewer"] is String && report["synthetic"] is Boolean && report["hostRules"] == ReviewedKifHornPlan.rules)
        val fields = s_["inputCid", "frameId", "snapshotCid", "revision", "readCid", "approvalCid", "decisionCid", "reviewer", "synthetic",
            "policy", "scope", "coverage", "selectionCount", "selectionLimit", "status", "semantics", "firstProofOnly", "query", "proof", "checkedProof", "sourceEvidence", "hostRules",
            "execution", "solverStepBudget",
            "nativeBinarySha256", "nativeAdapterSha256", "sumoNixRevision"]
        val context = fields.view.associateWith { report[it] } + _m["mlirNixRevision" j SumoNixBridge.MLIR_REVISION]
        for (key in s_["inputCid", "frameId", "snapshotCid", "readCid", "approvalCid", "decisionCid"].view) cid(report[key])
        for (key in s_["nativeBinarySha256", "nativeAdapterSha256"].view) ContentId("sha256:" + text(report[key]))
        val revision = (report["revision"] as? Number)?.toDouble() ?: error("Native revision required")
        require(revision.isFinite() && revision >= 0 && revision <= 9_007_199_254_740_991.0 && revision % 1.0 == 0.0)
        // Preflight complete context before canonical encoding or constructing any graph.
        var nodes = 0; var chars = 0L
        fun bounded(value: Any?, depth: Int) {
            if (++nodes > 100_000 || depth > 96) throw ProfileRefused()
            when (value) {
                is String -> {
                    if (value.length > MAX_TERM) throw ProfileRefused()
                    value.encodeToByteArray(throwOnInvalidSequence = true)
                    chars += value.length
                    if (chars > MAX_CHARS) throw ProfileRefused()
                }
                is Map<*, *> -> { if (value.size > 4096) throw ProfileRefused(); value.forEach { (k, v) -> bounded(k, depth + 1); bounded(v, depth + 1) } }
                is List<*> -> { if (value.size > 4096) throw ProfileRefused(); value.forEach { bounded(it, depth + 1) } }
                null, is Number, is Boolean -> Unit
                else -> error("Unexpected native context value")
            }
        }
        bounded(context, 0)
        val evidence = map(report["sourceEvidence"])
        require(evidence.size == integer(report["selectionCount"]) && evidence.size <= ReviewedKifHornPlan.MAX_SELECTIONS)
        for ((id, raw) in evidence) {
            val q = map(raw)
            require(q["kind"] == "reviewed-kif-native-occurrence/1" && id == "selection:" + ContentId.of(encode(q)).hex &&
                q["readCid"] == report["readCid"] && q["snapshotCid"] == report["snapshotCid"] && q["approvalCid"] == report["approvalCid"])
            cid(q["originalCid"]); cid(q["textCid"])
            require(integer(q["end"]) > integer(q["begin"]) && integer(q["end"]) - integer(q["begin"]) == text(q["quote"]).length)
        }
        val checked = report["checkedProof"]?.let(::map)
        require(report["status"] in _s["SUPPORTED", "UNKNOWN"] && (report["status"] == "UNKNOWN") == (checked == null) &&
            (checked == null) == (report["proof"] == null)) { "Native proof status differs" }
        val steps = checked?.let { rows(it["steps"]).map(::map) }.orEmpty()
        val catalog = checked?.let { map(it["sources"]) }.orEmpty()
        require(catalog.size <= 128)
        val byPath = steps.associateBy { path(it["path"]) }
        require(byPath.size == steps.size)
        if (checked != null) {
            require(checked["kind"] == "native-horn-checked-proof/1" && checked["firstProofOnly"] == true && steps.isNotEmpty() &&
                checked["verification"] == "supplied-native-certificate-only-not-independent-inference")
            for (key in s_["inputCid", "frameId", "query", "semantics", "sumoNixRevision"].view)
                require(checked[key] == report[key]) { "Native checked frame differs" }
            require(checked["mlirNixRevision"] == SumoNixBridge.MLIR_REVISION)
            require(checked["referenceMode"] == "normalized-export-atoms-and-rule-scoped-variables-not-source-spans")
        }
        val sourceKinds = catalog.mapValues { (key, raw) ->
            val source = map(raw)
            require(source.keys == _s["id", "sourceCid", "sourceRevision", "review", "text", "sourceNodeId"] &&
                key == source["sourceNodeId"] && key == ContentId.of(encode(source.filterKeys { it != "sourceNodeId" })).value &&
                source["review"] == "APPROVED") { "Native source receipt differs" }
            cid(source["sourceCid"])
            require(text(source["sourceRevision"]).length in 1..1024)
            val id = text(source["id"])
            if (id.startsWith("host-rule:")) {
                val index = id.removePrefix("host-rule:").toIntOrNull() ?: error("Native host rule identity required")
                require(index in 1..ReviewedKifHornPlan.rules.size && id == "host-rule:$index" &&
                    source["text"] == ReviewedKifHornPlan.rules[index - 1] && source["sourceRevision"] == ReviewedKifHornPlan.POLICY &&
                    source["sourceCid"] == ContentId.of(text(source["text"]).encodeToByteArray()).value)
                "host-rule-normalized-form"
            } else {
                val q = map(evidence[id])
                require(source["text"] == q["quote"] && source["sourceCid"] == q["textCid"])
                "reviewed-kif-exact-quotation"
            }
        }
        val usedSources = mutableSetOf<String>()
        fun refs(value: Any?): List<String> = rows(value, 128).map { cid(it).also { id ->
            require(id in catalog) { "Dangling native source reference" }; usedSources.add(id)
        } }.also { require(it.distinct().size == it.size) { "Repeated native source reference" } }
        val visited = mutableSetOf<List<Int>>()
        fun visit(raw: Any?, at: List<Int>) {
            require(visited.add(at))
            val node = map(raw); val step = byPath[at] ?: error("Missing native step")
            val normal = KifSemantic.decode(text(node["fact"])).syntax.toKifString()
            require(step["normalForm"] == normal)
            val fact = KifSemantic.decode(normal).formula as? KifFormula.Predicate ?: error("Native scalar fact required")
            val arguments = rows(step["arguments"], 16)
            require(step["predicate"] == fact.name && arguments.size == fact.arguments.size)
            for (i in arguments.indices) require(arguments[i] == when (val t = fact.arguments[i]) {
                is KifTerm.Constant -> _m["kind" j "constant", "token" j t.token]
                is KifTerm.Literal -> _m["kind" j "literal", "spelling" j t.spelling]
                else -> error("Native scalar argument required")
            }) { "Native typed argument projection differs" }
            val rawSources = rows(node["sources"], 128).map(::map)
            val rawIds = rawSources.map { it["sourceNodeId"] }
            require(rawIds.isNotEmpty() && rawIds.distinct().size == rawIds.size)
            rawSources.forEach { require(map(catalog[it["sourceNodeId"]]) == it) }
            if (node["asserted"] == true) {
                require(step["kind"] == "asserted" && step["witnessMode"] == "any-one-asserted-source" && refs(step["sourceNodeIds"]) == rawIds)
            } else {
                val ruleRefs = refs(step["ruleSourceNodeIds"])
                require(step["kind"] == "derived" && step["rule"] == node["rule"] && ruleRefs == rawIds && ruleRefs.isNotEmpty())
                fun form(value: Any?): String = if (value is List<*>) "(" + value.joinToString(" ") { form(it) } + ")" else text(value)
                val pattern = rows(step["rulePattern"], 3)
                require(pattern.size == 3 && pattern[0] == "=>")
                for (source in ruleRefs) require(KifSemantic.decode(form(pattern)).formula ==
                    KifSemantic.decode(text(map(catalog[source])["text"])).formula) { "Native rule pattern/source projection differs" }
                val body = rows(pattern[1], 17)
                val patterns = if (body.firstOrNull() == "and") body.drop(1) else _l[body]
                val uses = rows(step["premises"], 16).map(::map); val children = rows(node["from"], 16)
                require(uses.size == children.size && uses.size == patterns.size && uses.isNotEmpty())
                for (i in uses.indices) {
                    require(integer(uses[i]["ordinal"]) == i && path(uses[i]["path"]) == at + i && uses[i]["pattern"] == patterns[i])
                    visit(children[i], at + i)
                }
                val bindings = rows(step["bindings"], 256).map(::map)
                val variableNames = KifSemantic.decode(form(pattern)).freeVariables
                require(bindings.map { it["variable"] }.distinct().size == bindings.size && bindings.map { it["variable"] }.toSet() == variableNames)
                for (binding in bindings) require(binding["rule"] == step["rule"] && refs(binding["ruleSourceNodeIds"]) == ruleRefs &&
                    refs(binding["originalVariableSourceNodeIds"]).all { source -> source in ruleRefs && text(binding["variable"]) in
                        KifSemantic.decode(text(map(catalog[source])["text"])).freeVariables } && text(binding["variable"]).startsWith('?'))
            }
        }
        if (checked != null) visit(report["proof"], emptyList())
        require(visited == byPath.keys) { "Unreachable native step" }
        require(usedSources == catalog.keys) { "Unreferenced native source catalog entry" }
        val projectionCid = ContentId.of(encode(_m["kind" j SCHEMA, "context" j context]))
        val graph = graphIri(projectionCid.value); val store = QuadStore(); chars = 0
        fun term(value: RdfTerm): String {
            val encoded = buildString { for (c in value.toTurtle()) if (c.code < 32) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c) }
            if (encoded.length > MAX_TERM) throw ProfileRefused()
            require(TurtleRdf.parseTerm(encoded) == value)
            return encoded
        }
        val g = term(RdfTerm.Iri(graph))
        fun emit(subject: String, predicate: String, value: RdfTerm) {
            val s = term(RdfTerm.Iri(subject)); val p = term(RdfTerm.Iri(VOCAB + predicate)); val o = term(value)
            if (store.contains(s, p, o, g)) return
            chars += s.length + p.length + o.length + g.length
            if (store.size >= MAX_QUADS || chars > MAX_CHARS) throw ProfileRefused()
            store.add(s, p, o, g)
        }
        fun literal(s: String, p: String, v: Any?) = emit(s, p, RdfTerm.Literal(text(v)))
        fun scalar(node: String, value: Any?) {
            val field = map(value); val kind = text(field["kind"])
            require(kind in _s["constant", "literal"] && field.keys == _s["kind", if (kind == "constant") "token" else "spelling"])
            literal(node, "termKind", kind); literal(node, "spelling", field[if (kind == "constant") "token" else "spelling"])
        }
        literal(graph + "/frame", "kind", "checked-native-first-proof"); literal(graph + "/frame", "status", report["status"])
        for ((key, raw) in catalog) {
            val source = map(raw); val iri = sourceIri(graph, text(key)); val mode = sourceKinds.getValue(key)
            literal(iri, "referenceMode", mode); literal(iri, "exportText", source["text"])
            emit(iri, "sourceCid", RdfTerm.Iri("urn:sha256:" + ContentId(text(source["sourceCid"])).hex))
            literal(iri, "sourceRevision", source["sourceRevision"])
            if (mode == "reviewed-kif-exact-quotation") {
                val q = map(evidence[source["id"]])
                literal(iri, "quote", q["quote"]); literal(iri, "selectionId", q["selectionId"])
                emit(iri, "originalCid", RdfTerm.Iri("urn:sha256:" + ContentId(text(q["originalCid"])).hex))
                for (field in s_["begin", "end"].view) emit(iri, field, RdfTerm.Literal(integer(q[field]).toString(), RdfVocab.XSD + "integer"))
                literal(iri, "offsetUnit", "utf16-code-units-half-open")
            }
        }
        for (step in steps) {
            val at = path(step["path"]); val iri = stepIri(graph, at)
            literal(iri, "kind", step["kind"]); literal(iri, "normalForm", step["normalForm"]); literal(iri, "predicate", step["predicate"])
            literal(iri, "sourceReferenceRole", if (step["kind"] == "asserted") "asserted-witness-alternatives" else "rule-receipt-reference")
            if (step["kind"] == "asserted") literal(iri, "witnessMode", "any-one-asserted-source")
            for ((i, value) in rows(step["arguments"], 16).withIndex()) { val arg = "$iri/argument/$i"; emit(iri, "argument", RdfTerm.Iri(arg)); scalar(arg, value) }
            val sourceRefs = if (step["kind"] == "asserted") refs(step["sourceNodeIds"]) else refs(step["ruleSourceNodeIds"])
            sourceRefs.forEach { emit(iri, "sourceReference", RdfTerm.Iri(sourceIri(graph, it))) }
            if (step["kind"] == "derived") {
                literal(iri, "rule", step["rule"])
                for (raw in rows(step["premises"], 16)) {
                    val use = map(raw); val ordinal = integer(use["ordinal"]); val node = "$iri/use/$ordinal"
                    emit(iri, "premiseUse", RdfTerm.Iri(node)); emit(node, "ordinal", RdfTerm.Literal(ordinal.toString(), RdfVocab.XSD + "integer"))
                    emit(node, "childStep", RdfTerm.Iri(stepIri(graph, path(use["path"]))))
                }
                for ((i, raw) in rows(step["bindings"], 256).withIndex()) {
                    val binding = map(raw); val node = "$iri/binding/$i"
                    emit(iri, "binding", RdfTerm.Iri(node)); literal(node, "variable", binding["variable"]); scalar(node, binding["term"])
                    refs(binding["ruleSourceNodeIds"]).forEach { emit(node, "ruleSource", RdfTerm.Iri(sourceIri(graph, it))) }
                    refs(binding["originalVariableSourceNodeIds"]).forEach { emit(node, "originalVariableSource", RdfTerm.Iri(sourceIri(graph, it))) }
                }
            }
        }
        val descriptor = _m["schema" j SCHEMA, "dataset" j DATASET, "graph" j graph, "projectionCid" j projectionCid.value,
            "quads" j store.size, "persistence" j "request-local", "sourceReferenceMode" j "checked-first-proof-with-complete-selection-context"] +
            fields.view.filter { it !in _s["proof", "checkedProof", "sourceEvidence", "hostRules"] }.view.associateWith { report[it] }
        return Projection(store, graph, projectionCid, descriptor)
    }
}
