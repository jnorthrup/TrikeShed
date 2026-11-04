package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

object EnglishLegalQueryContract {
    const val TYPE = "document.english.patterns.query"
    fun contract() = LcncPortContract(TYPE, "Query retained legal language candidates", _l["inspectionCid", "mode", "program"],
        _l["dataset", "query", "results", "source"],
        inputKinds = _m["inspectionCid" j "id", "mode" j "text", "program" j "json"],
        outputKinds = _m["dataset" j "text", "query" j "text", "results" j "json", "source" j "json"],
        params = _m["inspectionCid" j LcncPortContract.LcncParamSpec(ph = "Parent retained English inspection CID"),
            "mode" j LcncPortContract.LcncParamSpec(opts = _l["snapshot", "current"], ph = "Explicit historical or checked current source"),
            "program" j LcncPortContract.LcncParamSpec(ta = true, ph = "Inspection-only SPARQL visual syntax; same-sentence joins show cooccurrence, not applicability")])
}
