package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.rdf.*

/** Two fixed inspection queries. These helpers check projection links, never native execution or review authority. */
object NativeHornQueryView {
    class Starter internal constructor(val projectionCid: String, val path: List<Int>, val bindings: Boolean, val program: LcncProgram) {
        val variables = if (bindings) _l["binding", "variable", "kind", "value", "source", "sourceCid", "form", "referenceMode"]
            else _l["use", "ordinal", "child", "childForm", "kind", "source", "sourceCid", "form"]
        val query: String get() = SparqlGraph.lower(program).text
    }
    class Answer(val value: Map<*, *>, val rows: List<Map<*, *>>)
    private fun map(v: Any?) = NativeHornProofGraph.map(v)
    private fun text(v: Any?) = NativeHornProofGraph.text(v)
    private fun rows(v: Any?, bound: Int = 4096) = NativeHornProofGraph.rows(v, bound)

    fun starter(report: Map<*, *>, path: List<Int> = emptyList(), bindings: Boolean): Starter {
        val projection = NativeHornProofGraph.project(report)
        val steps = rows(map(report["checkedProof"])["steps"]).map(::map)
        require(steps.single { NativeHornProofGraph.path(it["path"]) == path }["kind"] == "derived") { "Choose an actual derived native step" }
        val step = RdfTerm.Iri(NativeHornProofGraph.stepIri(projection.graph, path)).toTurtle()
        val patterns = if (bindings) s_[Triple(step, "binding", "?binding"), Triple("?binding", "variable", "?variable"),
            Triple("?binding", "termKind", "?kind"), Triple("?binding", "spelling", "?value"), Triple("?binding", "ruleSource", "?source"),
            Triple("?source", "sourceCid", "?sourceCid"), Triple("?source", "exportText", "?form"), Triple("?source", "referenceMode", "?referenceMode")]
        else s_[Triple(step, "premiseUse", "?use"), Triple("?use", "ordinal", "?ordinal"), Triple("?use", "childStep", "?child"),
            Triple("?child", "normalForm", "?childForm"), Triple("?child", "kind", "?kind"), Triple("?child", "sourceReference", "?source"),
            Triple("?source", "sourceCid", "?sourceCid"), Triple("?source", "exportText", "?form")]
        val variables = if (bindings) _l["binding", "variable", "kind", "value", "source", "sourceCid", "form", "referenceMode"]
            else _l["use", "ordinal", "child", "childForm", "kind", "source", "sourceCid", "form"]
        val nodes = mutableListOf(LcncNode("select", SparqlGraph.SELECT)); val wires = mutableListOf<LcncWire>()
        for ((i, variable) in variables.withIndex()) {
            nodes.add(LcncNode("v$i", SparqlGraph.VARIABLE, _m["name" j "?$variable", "ord" j i.toString()]))
            wires.add(LcncWire("v$i", "variable", "select", "variables"))
        }
        for ((i, pattern) in patterns.view.withIndex()) {
            nodes.add(LcncNode("p$i", SparqlGraph.PATTERN, _m["subject" j pattern.first,
                "predicate" j RdfTerm.Iri(NativeHornProofGraph.VOCAB + pattern.second).toTurtle(), "object" j pattern.third,
                "graph" j RdfTerm.Iri(projection.graph).toTurtle()]))
            wires.add(LcncWire("p$i", "pattern", "select", "patterns"))
        }
        return Starter(projection.projectionCid.value, path.toList(), bindings, LcncProgram("native-horn-step-inspection", nodes.toSeries(),
            wires.toSeries(), controls = LcncConfixControls(inspectionOnly = true)))
    }

    /** Exact typed row coverage of the selected projection, not another theorem checker. */
    fun answer(expected: Map<*, *>, response: Map<*, *>, starter: Starter): Answer {
        val projection = NativeHornProofGraph.project(expected)
        require(projection.projectionCid.value == starter.projectionCid && NativeHornProofGraph.project(response).projectionCid == projection.projectionCid) {
            "Native proof/selection context changed"
        }
        val value = map(response["quadQuery"])
        require(value["kind"] == "reviewed-native-horn-quad-query/1" && value["schema"] == NativeHornProofGraph.SCHEMA &&
            value["dataset"] == NativeHornProofGraph.DATASET && value["graph"] == projection.graph && value["query"] == starter.query &&
            value["persistence"] == "request-local" && value["authority"] == "authenticated-reviewed-read-not-a-sink-permit")
        val physical = map(value["physicalSource"])
        require(physical["selected"] == "quad-store" && physical["sources"] == _l["quad-store"] && physical["scope"] == "raw-matched-data" &&
            physical["reviewAuthority"] == "not-established" && physical["bindingProvenance"] == "not-established" && physical["sampling"] == "single-source")
        val provenance = map(value["provenance"])
        for ((key, v) in projection.descriptor) require(if (v is Number && provenance[key] is Number)
            v.toDouble() == (provenance[key] as Number).toDouble() else provenance[key] == v) { "Native projection provenance differs: $key" }
        val checkedAsOf = (provenance["checkedAsOf"] as? Number)?.toDouble() ?: error("Native query asOf required")
        require(checkedAsOf.isFinite() && checkedAsOf >= 0 && checkedAsOf <= 9_007_199_254_740_991.0 && checkedAsOf % 1.0 == 0.0)
        val checked = map(expected["checkedProof"]); val sources = map(checked["sources"])
        val documentRevisions = sources.values.map(::map).filter { !text(it["id"]).startsWith("host-rule:") }.map { it["sourceRevision"] }.distinct()
        require(documentRevisions.size == 1 && provenance["sourceRevision"] == documentRevisions.single())
        val steps = rows(checked["steps"]).map(::map).associateBy { NativeHornProofGraph.path(it["path"]) }
        val step = steps.getValue(starter.path); val iri = NativeHornProofGraph.stepIri(projection.graph, starter.path)
        fun uri(v: String) = _m["type" j "uri", "value" j v]
        fun literal(v: Any?) = _m["type" j "literal", "value" j text(v)]
        fun source(id: String) = NativeHornProofGraph.sourceIri(projection.graph, id)
        fun sourceCid(id: String) = uri("urn:sha256:" + text(map(sources[id])["sourceCid"]).removePrefix("sha256:"))
        val expectedRows = mutableListOf<Map<String, Any?>>()
        if (starter.bindings) for ((i, raw) in rows(step["bindings"], 256).withIndex()) {
            val binding = map(raw); val term = map(binding["term"]); val kind = text(term["kind"])
            for (rawSource in rows(binding["ruleSourceNodeIds"], 128)) {
                val id = text(rawSource); val record = map(sources[id])
                expectedRows.add(_m["binding" j uri("$iri/binding/$i"), "variable" j literal(binding["variable"]), "kind" j literal(kind),
                    "value" j literal(term[if (kind == "constant") "token" else "spelling"]), "source" j uri(source(id)),
                    "sourceCid" j sourceCid(id), "form" j literal(record["text"]),
                    "referenceMode" j literal(if (text(record["id"]).startsWith("host-rule:")) "host-rule-normalized-form" else "reviewed-kif-exact-quotation")])
            }
        } else for (raw in rows(step["premises"], 16)) {
            val use = map(raw); val ordinal = NativeHornProofGraph.integer(use["ordinal"])
            val path = NativeHornProofGraph.path(use["path"]); val child = steps.getValue(path)
            for (rawSource in rows(child[if (child["kind"] == "asserted") "sourceNodeIds" else "ruleSourceNodeIds"], 128)) {
                val id = text(rawSource)
                expectedRows.add(_m["use" j uri("$iri/use/$ordinal"), "ordinal" j _m["type" j "literal", "value" j ordinal.toString(), "datatype" j (RdfVocab.XSD + "integer")],
                    "child" j uri(NativeHornProofGraph.stepIri(projection.graph, path)), "childForm" j literal(child["normalForm"]),
                    "kind" j literal(child["kind"]), "source" j uri(source(id)), "sourceCid" j sourceCid(id), "form" j literal(map(sources[id])["text"])])
            }
        }
        val result = map(value["results"])
        require(map(result["head"])["vars"] == starter.variables)
        val returned = rows(map(result["results"])["bindings"], 20_000).map(::map)
        val order: Map<Map<*, *>, Int> = expectedRows.withIndex().associate { (it.value j it.index).pair }
        require(order.size == expectedRows.size && returned.size == expectedRows.size && returned.toSet() == expectedRows.toSet()) {
            "Native query omitted, repeated or substituted a typed projection row"
        }
        return Answer(value, returned.sortedBy { order.getValue(it) })
    }
}
