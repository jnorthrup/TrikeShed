package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/** Fixed local Choice over a checked corpus search, never a caller-authored endpoint or predicate. */
object CamelCorpusRouting {
    const val TYPE = "document.english.corpus.route"
    const val NAME = "preset-english-corpus-triage"
    const val KIND = "english-corpus-camel-route/1"
    const val FROM = "direct:english-corpus-triage"
    const val HEADER = "CorpusReceiptCid"
    const val LANE = "CorpusInspectionLane"
    const val MATCHES = "has-lexical-matches"
    const val EMPTY = "no-lexical-matches"

    fun lane(signal: String): String = when (signal) {
        MATCHES -> "inspect-source-sentences"
        EMPTY -> "no-lexical-match"
        else -> error("Unknown checked corpus routing signal")
    }

    fun plan(receiptCid: String, corpusCid: String, matrixCid: String, topicCid: String?): CamelRoutePlan {
        s_[receiptCid, corpusCid, matrixCid].view.forEach { ContentId(it) }; topicCid?.let(::ContentId)
        val headers = s_[CamelRouteStep.SetHeader(HEADER, receiptCid), CamelRouteStep.SetHeader("CorpusCid", corpusCid),
            CamelRouteStep.SetHeader("MatrixCid", matrixCid)]
        val steps = headers.view.toMutableList<CamelRouteStep>()
        topicCid?.let { steps.add(CamelRouteStep.SetHeader("TopicCid", it)) }
        steps.add(CamelRouteStep.Choice(s_[
            CamelRouteBranch(MATCHES, s_[CamelRouteStep.SetHeader(LANE, lane(MATCHES))]),
            CamelRouteBranch(EMPTY, s_[CamelRouteStep.SetHeader(LANE, lane(EMPTY))]),
        ], s_[CamelRouteStep.Stop]))
        steps.add(CamelRouteStep.SetBody(receiptCid)); steps.add(CamelRouteStep.Stop)
        return CamelRoutePlan(FROM, steps.toSeries())
    }

    fun checkedReply(reply: Map<*, *>, receiptCid: String, corpusCid: String, matrixCid: String, topicCid: String?, signal: String): String {
        val headers = reply["headers"] as? Map<*, *> ?: error("Camel reply headers required")
        val selected = lane(signal)
        require(reply["from"] == FROM && reply["reply"] == receiptCid && headers[HEADER] == receiptCid &&
            headers["CorpusCid"] == corpusCid && headers["MatrixCid"] == matrixCid && headers["TopicCid"] == topicCid &&
            headers[LANE] == selected) { "Camel changed the checked corpus identity or Choice outcome" }
        require(reply["plan"] == plan(receiptCid, corpusCid, matrixCid, topicCid).toValue()) { "Camel reply route differs" }
        return selected
    }

    fun contract() = LcncPortContract(TYPE, "Route a checked corpus lexical search", _l["receiptCid", "mode"],
        _l["receiptCid", "corpusCid", "matrixCid", "topicCid", "lane", "source", "execution", "terminalCid"],
        inputKinds = _m["receiptCid" j "id", "mode" j "text"],
        outputKinds = _m["receiptCid" j "id", "corpusCid" j "id", "matrixCid" j "id", "topicCid" j "json",
            "lane" j "text", "source" j "json", "execution" j "json", "terminalCid" j "id"],
        params = _m["receiptCid" j LcncPortContract.LcncParamSpec(ph = "Retained corpus receipt with an explicit lexical query"),
            "mode" j LcncPortContract.LcncParamSpec(opts = _l["snapshot", "current"], ph = "Same explicit mode as the corpus receipt")],
        isEffect = true)

    val info = LcncPresetInfo(NAME, "Route a corpus search for inspection",
        "Rechecks one retained corpus search and uses a local Camel Choice to separate matching sentences from an empty lexical result.",
        "A corpus search receipt CID, its explicit source mode and the installed local Camel module.",
        "The actual route outcome, unchanged corpus, matrix and optional topic identities, and retained execution metadata. Lexical matches are not evidence confidence.",
        "Run the corpus search first; copy its receipt CID here and choose the same current or snapshot mode, then Run.")

    fun build(receiptCid: String = "", mode: String = "snapshot"): LcncProgram {
        if (receiptCid.isNotEmpty()) ContentId(receiptCid)
        require(mode in _s["current", "snapshot"])
        return LcncProgram(NAME, s_[
            LcncNode("instructions", "note", _m["text" j "Choose the receipt CID produced by a corpus lexical search and the same explicit source mode. Run to inspect the local Camel Choice outcome. Matches retain the corpus's source sentences and token spans; ranking and topic weights are descriptive, not evidence confidence or legal approval."], x = 40.0, y = 20.0, width = 650.0),
            LcncNode("receipt", LcncContracts.SCOPE_IN, _m["name" j "receiptCid", "kind" j "id", "default" j receiptCid], x = 40.0, y = 230.0),
            LcncNode("mode", "text.value", _m["value" j mode], x = 40.0, y = 430.0),
            LcncNode("route", TYPE, x = 390.0, y = 230.0),
            LcncNode("outcome", "display", x = 740.0, y = 230.0),
            LcncNode("source", "display", x = 740.0, y = 430.0),
            LcncNode("lane", LcncContracts.SCOPE_OUT, _m["name" j "lane", "kind" j "text"], x = 1090.0, y = 230.0),
            LcncNode("terminal", LcncContracts.SCOPE_OUT, _m["name" j "terminalCid", "kind" j "id"], x = 1090.0, y = 430.0),
        ], s_[LcncWire("receipt", "value", "route", "receiptCid"), LcncWire("mode", "value", "route", "mode"),
            LcncWire("route", "execution", "outcome", "x"), LcncWire("route", "source", "source", "x"),
            LcncWire("route", "lane", "lane", "value"), LcncWire("route", "terminalCid", "terminal", "value")],
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.65), seq = 8)
    }
}
