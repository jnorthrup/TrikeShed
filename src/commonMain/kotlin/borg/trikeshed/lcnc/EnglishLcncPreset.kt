package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Inert local EIP plan construction and exact transport identity checks, never evidence approval. */
object EnglishLcncPreset {
    const val NAME = "preset-english-camel-query"
    const val COPULAR_NAME = "preset-english-copular-query"
    const val COPULAR_PATTERN_NAME = "preset-english-copular-pattern"
    const val ROUTE = "document.english.route"
    const val EVIDENCE = "document.english.evidence"
    const val HEADER = "EvidenceCid"
    private val cidPattern = Regex("sha256:[0-9a-f]{64}")
    private fun cid(value: Any?): String = (value as? String)?.also {
        require(cidPattern.matches(it)) { "Choose an exact retained English inspection CID" }
    } ?: error("Retained English inspection CID required")

    val info = LcncPresetInfo(NAME, "Route and query local English evidence",
        "Carries one retained English inspection through a local Camel route, checks its unchanged identity, and queries that explicit receipt dataset.",
        "A retained English inspection CID and the installed local Camel module. No model provider or network endpoint is used.",
        "Actual Camel transport headers, bounded SPARQL results and the selected receipt's source descriptor. Observations remain unreviewed.",
        "The receiptCid scope input's default, then the inspection-only visual-query program on the query-pattern node. Run explicitly.")

    val copularInfo = LcncPresetInfo(COPULAR_NAME, "Query copular syntax observations",
        "Finds parser copula and subject edges sharing one predicate node in an explicitly selected English inspection.",
        "A retained English inspection CID and an explicit current or historical source mode. No model or Camel module is needed.",
        "Full parser node identities, source words and source identity. Negated, modal and conditional sentences can match; these are not instance, subclass or positive-force assertions.",
        "Set receiptCid and source mode, then Run explicitly. Open the retained inspection for full quotations and qualifications; the eight-pattern result is not the complete sentence.")

    val copularPatternInfo = LcncPresetInfo(COPULAR_PATTERN_NAME, "Read a copular syntax diagram",
        "Shows the eight-pattern grammar used by the copular observation query. It is an inspection-only diagram, not an executable procedure.",
        "Nothing to inspect the diagram. Use Query copular syntax observations with a retained inspection to execute it.",
        "Two recorded dependency edges join on the same full parser node. No class assertion, polarity or applicability is inferred.",
        "Follow the shared predicate node, then inspect the copula and subject source nodes. Copies retain their full identities and can repeat word labels.")

    fun contracts(): List<LcncPortContract> = _l[
        LcncPortContract(ROUTE, "Local English evidence route", _l["receiptCid"], _l["route"],
            inputKinds = _m["receiptCid" j "id"], outputKinds = _m["route" j "json"]),
        LcncPortContract(EVIDENCE, "Check routed evidence identity", _l["expectedCid", "routed", "transport"], _l["receiptCid"],
            inputKinds = _m["expectedCid" j "id", "routed" j "text", "transport" j "json"], outputKinds = _m["receiptCid" j "id"]),
        EnglishObservationContract.contract(),
        EnglishNalContract.contract(),
        EnglishRelationContract.contract(),
        EnglishCorpusContract.contract(),
        EnglishLegalPatternsContract.contract(),
        EnglishLegalQueryContract.contract(),
    ]

    fun registry(): Map<String, LcncNodeRunner> = _m[
        ROUTE j LcncPureRunner { node, inputs ->
            require(node.params.isEmpty() && inputs.keys == _s["receiptCid"]) { "English evidence routing accepts only a receipt CID; endpoints and module options are fixed" }
            val receipt = cid(inputs["receiptCid"])
            val plan = CamelRoutePlan("direct:english-evidence", s_[
                CamelRouteStep.SetHeader(HEADER, receipt), CamelRouteStep.SetBody(receipt), CamelRouteStep.To("mock:english-evidence"),
            ])
            _m["route" j plan.toValue()]
        },
        EVIDENCE j LcncPureRunner { node, inputs ->
            require(node.params.isEmpty() && inputs.keys == _s["expectedCid", "routed", "transport"]) { "Unexpected English evidence transport fields" }
            val expected = cid(inputs["expectedCid"])
            val routed = cid(inputs["routed"])
            val transport = inputs["transport"] as? Map<*, *> ?: error("Camel transport receipt required")
            val headers = transport["headers"] as? Map<*, *> ?: error("Camel transport headers required")
            require(routed == expected && transport["reply"] == expected && headers[HEADER] == expected) {
                "Camel transport changed the selected evidence CID"
            }
            _m["receiptCid" j routed]
        },
    ]

    /** The shared visual-query grammar; this syntax graph is data supplied to the query owner. */
    fun queryPattern(): LcncProgram = LcncProgram("english-negation-pattern", s_[
        LcncNode("select", SparqlGraph.SELECT, x = 640.0, y = 120.0),
        LcncNode("marker", SparqlGraph.VARIABLE, _m["name" j "?marker", "ord" j "0"], x = 40.0, y = 40.0),
        LcncNode("quote", SparqlGraph.VARIABLE, _m["name" j "?quote", "ord" j "1"], x = 40.0, y = 160.0),
        LcncNode("kind", SparqlGraph.PATTERN, _m["subject" j "?marker",
            "predicate" j "<urn:trikeshed:english:observation:markerKind>", "object" j "\"negation\""], x = 300.0, y = 280.0),
        LcncNode("quotation", SparqlGraph.PATTERN, _m["subject" j "?marker",
            "predicate" j "<urn:trikeshed:english:observation:quote>", "object" j "?quote"], x = 300.0, y = 450.0),
    ], s_[LcncWire("marker", "variable", "select", "variables"), LcncWire("quote", "variable", "select", "variables"),
        LcncWire("kind", "pattern", "select", "patterns"), LcncWire("quotation", "pattern", "select", "patterns")],
        controls = LcncConfixControls(inspectionOnly = true))

    fun build(receiptCid: String = ""): LcncProgram = LcncProgram(NAME, s_[
        LcncNode("instructions", "note", _m["text" j "Choose a retained English inspection CID on receiptCid. This construction runs only a fixed local direct/mock Camel route; no network, model, rule admission or legal approval is wired. The CID is carried in the actual message body and EvidenceCid header, then checked before querying. Snapshot mode inspects retained historical observations; choose current mode explicitly to require current source revisions. The query-pattern value is an inspection-only SPARQL syntax graph; results remain parser/lexical observations."], x = 40.0, y = 20.0),
        LcncNode("receipt", LcncContracts.SCOPE_IN, _m["name" j "receiptCid", "kind" j "id", "default" j receiptCid], x = 40.0, y = 250.0),
        LcncNode("plan", ROUTE, x = 350.0, y = 250.0),
        LcncNode("dispatch", "vm.camel", _m["trust" j "OWN", "reach" j "LOCAL", "module" j "camel", "body" j "english-evidence"], x = 660.0, y = 250.0),
        LcncNode("verify", EVIDENCE, x = 990.0, y = 250.0),
        LcncNode("mode", "text.value", _m["value" j "snapshot"], x = 990.0, y = 440.0),
        LcncNode("query-pattern", "json.value", _m["value" j LcncProgramConfix.toJson(queryPattern())], x = 990.0, y = 580.0),
        LcncNode("query", EnglishObservationContract.TYPE, x = 1320.0, y = 250.0),
        LcncNode("bindings", "display", x = 1640.0, y = 200.0),
        LcncNode("source", "display", x = 1640.0, y = 400.0),
        LcncNode("transport", "display", x = 660.0, y = 580.0),
        LcncNode("dataset-out", LcncContracts.SCOPE_OUT, _m["name" j "dataset", "kind" j "text"], x = 1960.0, y = 180.0),
        LcncNode("results-out", LcncContracts.SCOPE_OUT, _m["name" j "results", "kind" j "json"], x = 1960.0, y = 340.0),
        LcncNode("source-out", LcncContracts.SCOPE_OUT, _m["name" j "source", "kind" j "json"], x = 1960.0, y = 500.0),
    ], s_[
        LcncWire("receipt", "value", "plan", "receiptCid"), LcncWire("plan", "route", "dispatch", "route?"),
        LcncWire("receipt", "value", "verify", "expectedCid"), LcncWire("dispatch", "routed", "verify", "routed"),
        LcncWire("dispatch", "receipt", "verify", "transport"), LcncWire("dispatch", "receipt", "transport", "x"),
        LcncWire("verify", "receiptCid", "query", "receiptCid"), LcncWire("mode", "value", "query", "mode"),
        LcncWire("query-pattern", "value", "query", "program"), LcncWire("query", "results", "bindings", "x"),
        LcncWire("query", "source", "source", "x"), LcncWire("query", "dataset", "dataset-out", "value"),
        LcncWire("query", "results", "results-out", "value"), LcncWire("query", "source", "source-out", "value"),
    ], view = LcncView(x = 20.0, y = 20.0, zoom = 0.65), seq = 15)

    /** Literal parser-edge joins only. Relation filters are ordered after shared-node binding. */
    fun copularPattern(): LcncProgram {
        val variables = s_["?head", "?subject", "?copula", "?subjectWord", "?predicateWord"]
        val patterns = s_[
            Triple("?cop", "relation", "\"cop\""),
            Triple("?cop", "governor", "?head"),
            Triple("?sub", "governor", "?head"),
            Triple("?sub", "relation", "\"nsubj\""),
            Triple("?cop", "dependent", "?copula"),
            Triple("?sub", "dependent", "?subject"),
            Triple("?subject", "word", "?subjectWord"),
            Triple("?head", "word", "?predicateWord")]
        val nodes = mutableListOf(LcncNode("select", SparqlGraph.SELECT, x = 1480.0, y = 400.0))
        val wires = mutableListOf<LcncWire>()
        for ((i, variable) in variables.view.withIndex()) {
            nodes.add(LcncNode("v$i", SparqlGraph.VARIABLE, _m["name" j variable, "ord" j i.toString()],
                x = 40.0, y = 40.0 + i * 150))
            wires.add(LcncWire("v$i", "variable", "select", "variables"))
        }
        for ((i, pattern) in patterns.view.withIndex()) {
            nodes.add(LcncNode("p$i", SparqlGraph.PATTERN, _m["subject" j pattern.first,
                "predicate" j "<urn:trikeshed:english:observation:${pattern.second}>", "object" j pattern.third],
                x = 340.0 + (i % 3) * 340, y = 40.0 + (i / 3) * 240))
            wires.add(LcncWire("p$i", "pattern", "select", "patterns"))
        }
        return LcncProgram(COPULAR_PATTERN_NAME, nodes.toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true), view = LcncView(x = 20.0, y = 20.0, zoom = 0.6), seq = 15)
    }

    /** Explicit read-only receipt query. No parser, model, inference admission or review is wired. */
    fun buildCopular(receiptCid: String = "", mode: String = "snapshot"): LcncProgram {
        if (receiptCid.isNotEmpty()) cid(receiptCid)
        require(mode in _s["snapshot", "current"]) { "Choose explicit current or snapshot English source mode" }
        return LcncProgram(COPULAR_NAME, s_[
            LcncNode("instructions", "note", _m["text" j "Choose a retained English inspection and source mode, then Run. Matches are syntax only: negated, modal and conditional sentences can match. The word result is not the complete sentence; open the inspection for exact quotations and markers. Copied grammar nodes are not additional source occurrences."], x = 40.0, y = 20.0, width = 640.0, height = 165.0),
            LcncNode("receipt", LcncContracts.SCOPE_IN, _m["name" j "receiptCid", "kind" j "id", "default" j receiptCid], x = 40.0, y = 230.0),
            LcncNode("mode", "text.value", _m["value" j mode], x = 40.0, y = 430.0),
            LcncNode("query-pattern", "json.value", _m["value" j LcncProgramConfix.toJson(copularPattern())], x = 370.0, y = 230.0),
            LcncNode("query", EnglishObservationContract.TYPE, x = 710.0, y = 230.0),
            LcncNode("bindings", "display", x = 1060.0, y = 150.0),
            LcncNode("source", "display", x = 1060.0, y = 410.0),
            LcncNode("dataset-out", LcncContracts.SCOPE_OUT, _m["name" j "dataset", "kind" j "text"], x = 1400.0, y = 140.0),
            LcncNode("results-out", LcncContracts.SCOPE_OUT, _m["name" j "results", "kind" j "json"], x = 1400.0, y = 340.0),
            LcncNode("source-out", LcncContracts.SCOPE_OUT, _m["name" j "source", "kind" j "json"], x = 1400.0, y = 540.0)
        ], s_[
            LcncWire("receipt", "value", "query", "receiptCid"), LcncWire("mode", "value", "query", "mode"),
            LcncWire("query-pattern", "value", "query", "program"), LcncWire("query", "results", "bindings", "x"),
            LcncWire("query", "source", "source", "x"), LcncWire("query", "dataset", "dataset-out", "value"),
            LcncWire("query", "results", "results-out", "value"), LcncWire("query", "source", "source-out", "value")
        ], view = LcncView(x = 20.0, y = 20.0, zoom = 0.7), seq = 11)
    }
}
