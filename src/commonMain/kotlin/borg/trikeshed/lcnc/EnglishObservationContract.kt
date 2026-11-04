package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** The procedure invokes the same receipt verifier and bounded visual-query owner as Documents. */
object EnglishObservationContract {
    const val TYPE = "document.english.query"
    fun contract() = LcncPortContract(TYPE, "Query English receipt observations",
        _l["receiptCid", "mode", "program"], _l["dataset", "query", "results", "source"],
        inputKinds = _m["receiptCid" j "id", "mode" j "text", "program" j "json"],
        outputKinds = _m["dataset" j "text", "query" j "text", "results" j "json", "source" j "json"],
        params = _m["receiptCid" j LcncPortContract.LcncParamSpec(ph = "Retained English inspection CID"),
            "mode" j LcncPortContract.LcncParamSpec(opts = _l["snapshot", "current"], ph = "Explicit historical snapshot or checked current source"),
            "program" j LcncPortContract.LcncParamSpec(ta = true, ph = "Inspection-only SPARQL visual syntax program")])
}
