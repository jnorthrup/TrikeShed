package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf

/** An offered local search over the existing checked corpus owner, not the model digest preset. */
object EnglishCorpusPreset {
    const val NAME = "preset-english-corpus-search"
    val info = LcncPresetInfo(NAME, "Find sentences in a retained English corpus",
        "Builds one selected corpus and searches its included reported lemmas with the existing BM25 index.",
        "Retained English inspection CIDs, explicit source mode and exact lemma terms; all processing runs locally.",
        "Ranked full source sentences and every matching original token span, tied to the same corpus, matrix, policy and optional topics.",
        "Set receiptCids, mode and query, then Run. Edit the corpus node's view or optional topics explicitly; topic weights and lexical scores are not evidence confidence.")

    fun build(receiptCids: List<String> = emptyList(), mode: String = "snapshot",
              terms: List<String> = emptyList(), limit: Int = 32): LcncProgram {
        receiptCids.forEach { ContentId(it) }
        require(mode in _s["current", "snapshot"]) { "Choose explicit current or snapshot corpus mode" }
        require(limit in 1..256) { "Corpus search result limit must be 1..256" }
        return LcncProgram(NAME, s_[
            LcncNode("instructions", "note", _m["text" j
                "Choose retained inspection CIDs, explicit current/snapshot mode and query {terms:[exact reported lemmas],limit:32}, then Run. Use exact lemmas from this corpus's wordcloud or statistical topic vocabulary. The same word selection applies to counts, topics and search. Scores are lexical relevance, not evidence confidence or legal truth. Every match retains its source quotation and token spans; repeated occurrences are not independent evidence. Runs locally on the selected retained inspections."],
                x = 40.0, y = 20.0, width = 700.0, height = 190.0),
            LcncNode("receipts", "json.value", _m["value" j jsonOf(receiptCids)], x = 40.0, y = 250.0),
            LcncNode("mode", "text.value", _m["value" j mode], x = 40.0, y = 440.0),
            LcncNode("query", "json.value", _m["value" j jsonOf(_m["terms" j terms, "limit" j limit])], x = 40.0, y = 630.0),
            LcncNode("corpus", EnglishCorpusContract.TYPE, _m["topics" j "null", "view" j "all-lexical"], x = 420.0, y = 270.0),
            LcncNode("matches", "display", x = 800.0, y = 150.0),
            LcncNode("terms", "display", x = 800.0, y = 390.0),
            LcncNode("topics", "display", x = 800.0, y = 630.0),
            LcncNode("source", "display", x = 1160.0, y = 630.0),
            LcncNode("search-out", LcncContracts.SCOPE_OUT, _m["name" j "search", "kind" j "json"], x = 1160.0, y = 150.0),
            LcncNode("receipt-out", LcncContracts.SCOPE_OUT, _m["name" j "receiptCid", "kind" j "id"], x = 1520.0, y = 150.0),
            LcncNode("terms-out", LcncContracts.SCOPE_OUT, _m["name" j "terms", "kind" j "json"], x = 1160.0, y = 350.0),
            LcncNode("topics-out", LcncContracts.SCOPE_OUT, _m["name" j "topics", "kind" j "json"], x = 1520.0, y = 350.0),
            LcncNode("source-out", LcncContracts.SCOPE_OUT, _m["name" j "source", "kind" j "json"], x = 1520.0, y = 630.0),
        ], s_[
            LcncWire("receipts", "value", "corpus", "receiptCids"), LcncWire("mode", "value", "corpus", "mode"),
            LcncWire("query", "value", "corpus", "query?"),
            LcncWire("corpus", "search", "matches", "x"), LcncWire("corpus", "terms", "terms", "x"),
            LcncWire("corpus", "topics", "topics", "x"), LcncWire("corpus", "source", "source", "x"),
            LcncWire("corpus", "search", "search-out", "value"), LcncWire("corpus", "receiptCid", "receipt-out", "value"),
            LcncWire("corpus", "terms", "terms-out", "value"), LcncWire("corpus", "topics", "topics-out", "value"),
            LcncWire("corpus", "source", "source-out", "value"),
        ], view = LcncView(x = 20.0, y = 20.0, zoom = 0.65), seq = 14)
    }
}
