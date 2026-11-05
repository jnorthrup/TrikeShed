package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.EnglishGuardAttachments
import borg.trikeshed.rdf.EnglishLegalGraph
import borg.trikeshed.rdf.RdfTerm

/** One bounded query and exact display joins over candidate observations, never condition interpretation. */
object EnglishGuardQueryView {
    val variables: List<String> get() = _l["attachment", "governor", "governorWord", "subordinate", "subordinateWord", "relation", "normIssue", "sentence"]
    class Starter(val candidateCid: String, val inspectionCid: String, val graph: String, val program: LcncProgram) {
        val query: String get() = SparqlGraph.lower(program).text
    }
    class Row(val sentenceIndex: Int, val ordinal: Int, val attachment: Map<*, *>, val bindings: Map<*, *>)
    class Answer(val value: Map<*, *>, val rows: List<Row>)
    private fun map(raw: Any?) = raw as? Map<*, *> ?: error("Missing clause attachment query record")
    private fun rows(raw: Any?, limit: Int = 1024) = (raw as? List<*> ?: error("Missing clause attachment rows")).also {
        require(it.size <= limit) { "Clause attachment display limit" }
    }
    private fun integer(raw: Any?): Int {
        val n = (raw as? Number)?.toDouble() ?: error("Missing attachment ordinal")
        require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n == n.toInt().toDouble())
        return n.toInt()
    }
    fun section(candidate: Map<*, *>): Map<String, Any?>? = if ("guardAttachments" !in candidate) null
        else EnglishGuardAttachments.validate(candidate["guardAttachments"], rows(candidate["sentences"], 512))

    fun starter(candidate: Map<*, *>): Starter {
        requireNotNull(section(candidate)) { "Guard attachment section was not recorded" }
        val cid = ContentId(candidate["receiptCid"] as String)
        val inspection = ContentId(candidate["inspectionCid"] as String)
        val graph = EnglishLegalGraph.graph(cid)
        val patterns = s_[Triple("?attachment", "kind", "\"adverbial-attachment-observation\""),
            Triple("?attachment", "governorNode", "?governor"), Triple("?governor", "word", "?governorWord"),
            Triple("?attachment", "subordinateNode", "?subordinate"), Triple("?subordinate", "word", "?subordinateWord"),
            Triple("?attachment", "attachmentRelation", "?relation"), Triple("?attachment", "normConditionIssue", "?normIssue"),
            Triple("?attachment", "sentence", "?sentence")]
        val nodes = mutableListOf(LcncNode("select", SparqlGraph.SELECT)); val wires = mutableListOf<LcncWire>()
        for ((i, variable) in variables.withIndex()) {
            nodes.add(LcncNode("v$i", SparqlGraph.VARIABLE, _m["name" j "?$variable", "ord" j i.toString()]))
            wires.add(LcncWire("v$i", "variable", "select", "variables"))
        }
        for ((i, pattern) in patterns.view.withIndex()) {
            nodes.add(LcncNode("p$i", SparqlGraph.PATTERN, _m["subject" j pattern.first,
                "predicate" j RdfTerm.Iri(EnglishLegalGraph.VOCAB + pattern.second).toTurtle(),
                "object" j pattern.third, "graph" j RdfTerm.Iri(graph).toTurtle()]))
            wires.add(LcncWire("p$i", "pattern", "select", "patterns"))
        }
        return Starter(cid.value, inspection.value, graph, LcncProgram("observed-clause-attachments", nodes.toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true)))
    }

    /** Caller has already cross-checked the candidate's source and raw sentence observations. */
    fun answer(candidate: Map<*, *>, response: Map<*, *>, starter: Starter): Answer {
        val section = requireNotNull(section(candidate))
        require(candidate["receiptCid"] == starter.candidateCid && candidate["inspectionCid"] == starter.inspectionCid)
        require(response["dataset"] == "english-legal-candidate-observations" && response["query"] == starter.query) { "Clause query dataset or program differs" }
        val source = map(response["source"]); val expectedSource = map(candidate["source"])
        val mode = expectedSource["mode"]
        require(mode in _s["current", "snapshot"] && source["selected"] == "english-legal-candidate-observations" &&
            source["receiptCid"] == starter.candidateCid && source["candidateCid"] == starter.candidateCid &&
            source["inspectionCid"] == starter.inspectionCid && source["namedGraph"] == starter.graph &&
            source["originalCid"] == candidate["originalCid"] && source["extractedTextCid"] == candidate["extractedTextCid"] &&
            source["mode"] == mode && source["historical"] == (mode == "snapshot") &&
            source["reviewStatus"] == "unreviewed" && source["semanticAuthority"] == "not-established" &&
            source["producerAuthority"] == "not-established" && source["parserExecution"] == "not-reverified" &&
            source["bindingProvenance"] == "candidate-receipt-resources-with-parent-inspection-and-exact-source-spans" &&
            source["currentness"] == if (mode == "current") "checked-before-and-after-query" else "not-checked") { "Clause query source identity or authority differs" }
        require(integer(source["quads"]) <= 20_000)
        val sentenceRows = rows(candidate["sentences"], 512).map(::map)
        val sentences = sentenceRows.associateBy { integer(it["index"]) }
        require(sentences.size == sentenceRows.size) { "Repeated clause sentence identity" }
        val nodesBySentence = sentences.mapValues { (_, sentence) -> rows(sentence["nodes"], 16_384).map(::map).associateBy { it["id"] } }
        val expected = linkedMapOf<String, Join<Map<*, *>, Map<*, *>>>()
        for (raw in rows(section["sentences"], 512)) {
            val sentence = map(raw); val index = integer(sentence["index"])
            require(index in sentences) { "Unknown clause sentence identity" }
            for (rawAttachment in rows(sentence["attachments"])) {
                val attachment = map(rawAttachment); val ordinal = integer(attachment["ordinal"])
                require(expected.put(starter.graph + "/sentence/$index/guard-attachment/$ordinal", sentence j attachment) == null) {
                    "Repeated attachment resource identity"
                }
            }
        }
        val result = map(response["results"])
        require(map(result["head"])["vars"] == variables) { "Clause query columns differ" }
        val bindings = rows(map(result["results"])["bindings"])
        require(bindings.size == expected.size) { "Clause query omitted or repeated observed attachments" }
        fun term(row: Map<*, *>, name: String, type: String): String {
            val value = map(row[name])
            require(value.keys == _s["type", "value"] && value["type"] == type) { "Clause query RDF term kind differs" }
            return value["value"] as? String ?: error("Missing clause query value")
        }
        val seen = mutableSetOf<String>()
        val checked = bindings.map { raw ->
            val row = map(raw); require(row.keys == variables.toSet()) { "Clause query binding fields differ" }
            val iri = term(row, "attachment", "uri"); require(seen.add(iri)) { "Repeated clause attachment" }
            val (sentence, attachment) = expected[iri] ?: error("Unknown clause attachment resource")
            val index = integer(sentence["index"]); val ordinal = integer(attachment["ordinal"])
            val sentenceIri = starter.graph + "/sentence/$index"
            val nodes = nodesBySentence.getValue(index)
            val governor = nodes.getValue(attachment["governorNodeId"])
            val subordinate = nodes.getValue(attachment["subordinateNodeId"])
            require(term(row, "governor", "uri") == "$sentenceIri/node/${governor["id"]}" &&
                term(row, "subordinate", "uri") == "$sentenceIri/node/${subordinate["id"]}" &&
                term(row, "governorWord", "literal") == governor["word"] && term(row, "subordinateWord", "literal") == subordinate["word"] &&
                term(row, "relation", "literal") == map(attachment["dependency"])["relation"] &&
                term(row, "normIssue", "literal") == (sentence["normConditionIssue"] ?: "none-reported") &&
                term(row, "sentence", "uri") == sentenceIri) { "Clause query binding differs from its exact source observation" }
            Row(index, ordinal, attachment, row)
        }.sortedWith(compareBy({ it.sentenceIndex }, { it.ordinal }))
        return Answer(response, checked)
    }
}
