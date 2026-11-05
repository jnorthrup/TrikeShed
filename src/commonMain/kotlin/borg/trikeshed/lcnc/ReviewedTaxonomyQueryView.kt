package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfVocab

/** Two inspection-only evidence queries and their display bindings; never review authority or inference. */
object ReviewedTaxonomyQueryView {
    class Starter(val proofId: String, val english: Boolean, val program: LcncProgram) {
        val query: String get() = SparqlGraph.lower(program).text
        val variables: List<String> get() = if (english) _l["ordinal", "source", "rule", "kifQuote", "englishQuote"]
            else _l["ordinal", "source", "rule", "kifQuote"]
    }
    class Row(val ordinal: Int, val sourceNodeId: String, val bindings: Map<*, *>)
    class Answer(val value: Map<*, *>, val rows: List<Row>)

    private fun map(raw: Any?) = raw as? Map<*, *> ?: error("Missing reviewed query record")
    private fun rows(raw: Any?, bound: Int) = (raw as? List<*> ?: error("Missing reviewed query rows")).also {
        require(it.size <= bound) { "Reviewed query rows exceed bound" }
    }
    private fun integer(raw: Any?): Long {
        val n = (raw as? Number)?.toDouble() ?: error("Missing reviewed query number")
        require(n.isFinite() && n >= 0 && n <= 9007199254740991.0 && n == n.toLong().toDouble())
        return n.toLong()
    }
    private fun same(left: Any?, right: Any?) = if (left is Number && right is Number) integer(left) == integer(right) else left == right

    fun starter(report: Map<*, *>, proofId: String, english: Boolean): Starter {
        val plan = map(report["plan"])
        require(rows(plan["proofs"], 128).map { map(it)["nodeId"] }.count { it == proofId } == 1) { "Choose one returned proof" }
        require(!english || report["englishOrigin"] is Map<*, *> && report["englishWitnesses"] is List<*>) { "Verified English witnesses required" }
        val graph = ReviewedTaxonomyProofGraph.graphIri(report["readFrameCid"] as String)
        val proof = RdfTerm.Iri(ReviewedTaxonomyProofGraph.proofIri(graph, proofId)).toTurtle()
        val patterns = mutableListOf(
            Triple(proof, "premiseUse", "?use"), Triple("?use", "source", "?source"),
            Triple("?use", "ordinal", "?ordinal"), Triple("?use", "rule", "?rule"),
            Triple("?source", "kifQuotation", "?kif"), Triple("?kif", "quote", "?kifQuote"))
        if (english) {
            patterns.add(Triple("?source", "englishQuotation", "?english"))
            patterns.add(Triple("?english", "quote", "?englishQuote"))
        }
        val variables = if (english) s_["ordinal", "source", "rule", "kifQuote", "englishQuote"]
            else s_["ordinal", "source", "rule", "kifQuote"]
        val nodes = mutableListOf(LcncNode("select", SparqlGraph.SELECT))
        val wires = mutableListOf<LcncWire>()
        for ((index, variable) in variables.view.withIndex()) {
            nodes.add(LcncNode("v$index", SparqlGraph.VARIABLE, _m["name" j "?$variable", "ord" j index.toString()]))
            wires.add(LcncWire("v$index", "variable", "select", "variables"))
        }
        for ((index, pattern) in patterns.withIndex()) {
            nodes.add(LcncNode("p$index", SparqlGraph.PATTERN, _m["subject" j pattern.first,
                "predicate" j RdfTerm.Iri(ReviewedTaxonomyProofGraph.VOCAB + pattern.second).toTurtle(),
                "object" j pattern.third, "graph" j RdfTerm.Iri(graph).toTurtle()]))
            wires.add(LcncWire("p$index", "pattern", "select", "patterns"))
        }
        return Starter(proofId, english, LcncProgram("reviewed-proof-evidence", nodes.toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true)))
    }

    /** The caller authenticates the response and validates its complete proof/source/English bindings first. */
    fun answer(expected: Map<*, *>, response: Map<*, *>, starter: Starter): Answer {
        val plan = map(expected["plan"])
        val returnedPlan = map(response["plan"])
        for (key in s_["snapshotCid", "revision", "approvalCid", "decisionCid", "readCid", "readFrameCid",
            "sourceRevision", "goal", "status", "semantics", "reviewer", "synthetic"].view)
            require(same(expected[key], response[key])) { "Reviewed query changed $key" }
        for (key in s_["queryId", "frameId", "exportCid", "truncated"].view)
            require(plan[key] == returnedPlan[key]) { "Reviewed query proof frame differs" }
        val graph = ReviewedTaxonomyProofGraph.graphIri(expected["readFrameCid"] as String)
        val value = map(response["quadQuery"])
        require(value["kind"] == "reviewed-taxonomy-quad-query/1" && value["schema"] == ReviewedTaxonomyProofGraph.SCHEMA &&
            value["dataset"] == ReviewedTaxonomyProofGraph.DATASET && value["graph"] == graph && value["query"] == starter.query &&
            value["persistence"] == "request-local" && value["authority"] == "authenticated-reviewed-read-not-a-sink-permit") { "Unexpected reviewed query envelope" }
        val physical = map(value["physicalSource"])
        require(physical["selected"] == "quad-store" && physical["sources"] == _l["quad-store"] &&
            physical["scope"] == "raw-matched-data" && physical["reviewAuthority"] == "not-established" &&
            physical["bindingProvenance"] == "not-established" && physical["sampling"] == "single-source") { "Unexpected physical query source" }
        val provenance = map(value["provenance"])
        for (key in s_["snapshotCid", "revision", "approvalCid", "decisionCid", "readCid", "readFrameCid",
            "sourceRevision", "goal", "status", "semantics", "reviewer", "synthetic"].view)
            require(same(provenance[key], expected[key])) { "Reviewed query provenance differs: $key" }
        require(provenance["schema"] == ReviewedTaxonomyProofGraph.SCHEMA && provenance["dataset"] == ReviewedTaxonomyProofGraph.DATASET &&
            provenance["graph"] == graph && provenance["persistence"] == "request-local" &&
            provenance["planFrameId"] == plan["frameId"] && provenance["exportCid"] == plan["exportCid"] &&
            provenance["queryId"] == plan["queryId"] && provenance["truncated"] == plan["truncated"] &&
            provenance["englishAvailable"] == (expected["englishOrigin"] is Map<*, *>)) { "Reviewed query projection differs" }
        integer(provenance["checkedAsOf"])
        require(provenance["reviewer"] is String && provenance["synthetic"] is Boolean)
        require(integer(provenance["quads"]) <= 20_000)
        val results = map(value["results"])
        require(map(results["head"])["vars"] == starter.variables) { "Reviewed query projection columns differ" }
        val bindings = rows(map(results["results"])["bindings"], 256)
        val proof = rows(plan["proofs"], 128).map(::map).single { it["nodeId"] == starter.proofId }
        val premises = rows(proof["premises"], 256)
        val rules = rows(proof["rules"], 256)
        val sources = rows(plan["sources"], 256).map(::map).associateBy { it["nodeId"] }
        val english = if (!starter.english) emptyMap() else rows(expected["englishWitnesses"], 32).map(::map).associateBy { it["selectionId"] }
        require(bindings.size == premises.size) { "Reviewed query omitted or repeated proof premises" }
        fun binding(row: Map<*, *>, name: String, type: String, datatype: String? = null): String {
            val term = map(row[name])
            require(term.keys == (if (datatype == null) _s["type", "value"] else _s["type", "value", "datatype"]) &&
                term["type"] == type && term["datatype"] == datatype) { "Reviewed query RDF term kind differs" }
            return term["value"] as? String ?: error("Missing reviewed query RDF value")
        }
        val ordinals = mutableSetOf<Int>()
        val checked = bindings.map { raw ->
            val row = map(raw)
            require(row.keys == starter.variables.toSet()) { "Reviewed query binding columns differ" }
            val spelling = binding(row, "ordinal", "literal", RdfVocab.XSD + "integer")
            val ordinal = spelling.toIntOrNull() ?: error("Invalid proof ordinal")
            require(ordinal in premises.indices && spelling == ordinal.toString() && ordinals.add(ordinal)) { "Invalid or repeated proof ordinal" }
            val node = premises[ordinal] as String
            val source = sources.getValue(node)
            require(binding(row, "source", "uri") == ReviewedTaxonomyProofGraph.sourceIri(graph, node) &&
                binding(row, "rule", "literal") == rules[ordinal] && binding(row, "kifQuote", "literal") == source["text"]) { "Reviewed query premise differs" }
            if (starter.english) require(binding(row, "englishQuote", "literal") ==
                map(english.getValue(source["selectionId"])["englishQuotation"])["quote"]) { "Reviewed query English quotation differs" }
            Row(ordinal, node, row)
        }.sortedBy { it.ordinal }
        return Answer(value, checked)
    }
}
