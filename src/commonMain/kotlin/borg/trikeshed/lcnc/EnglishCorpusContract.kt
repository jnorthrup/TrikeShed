package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

object EnglishCorpusContract {
    const val TYPE = "document.english.corpus"
    fun contract() = LcncPortContract(TYPE, "English corpus statistics, topics and lexical search",
        _l["receiptCids", "mode", "topics?", "view?", "query?"], _l["receiptCid", "corpusCid", "matrixCid", "terms", "sentences", "occurrences", "matrix", "topics", "topicCid", "retrieval", "source", "search"],
        inputKinds = _m["receiptCids" j "json", "mode" j "text", "topics" j "json", "view" j "text", "query" j "json"],
        outputKinds = _m["receiptCid" j "id", "corpusCid" j "id", "matrixCid" j "id", "terms" j "json", "sentences" j "json",
            "occurrences" j "json", "matrix" j "json", "topics" j "json", "topicCid" j "json", "retrieval" j "json", "source" j "json", "search" j "json"],
        params = _m["receiptCids" j LcncPortContract.LcncParamSpec(ta = true, ph = "JSON array of 1..32 retained English inspection CIDs"),
            "mode" j LcncPortContract.LcncParamSpec(opts = _l["snapshot", "current"], ph = "Explicit historical snapshot or checked current sources"),
            "view" j LcncPortContract.LcncParamSpec(opts = _l["all-lexical", "content"], optional = true,
                ph = "Omit for all lexical lemmas; content keeps nouns, verbs, adjectives and legal markers"),
            "query" j LcncPortContract.LcncParamSpec(ta = true, optional = true,
                ph = "Omit for statistics only; {terms:[exact reported lemmas],limit:32}; lexical matches, not evidence confidence"),
            "topics" j LcncPortContract.LcncParamSpec(v = "null", ta = true, optional = true,
                ph = "null or {topics, iterations, seed, alpha, beta}; descriptive statistics only")])
}
