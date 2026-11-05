package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

object EnglishRelationContract {
    const val TYPE = "document.english.relations"
    fun contract() = LcncPortContract(TYPE, "Inspect ordered English relation roles",
        _l["receiptCid", "mode", "query?"], _l["report", "matches", "passages", "inspectionCid", "frameCid", "source", "status"],
        inputKinds = _m["receiptCid" j "id", "mode" j "text", "query" j "json"],
        outputKinds = _m["report" j "json", "matches" j "json", "passages" j "json", "inspectionCid" j "id",
            "frameCid" j "id", "source" j "json", "status" j "text"],
        params = _m["receiptCid" j LcncPortContract.LcncParamSpec(ph = "Exact retained English inspection CID"),
            "mode" j LcncPortContract.LcncParamSpec(opts = _l["snapshot", "current"]),
            "query" j LcncPortContract.LcncParamSpec(optional = true, ta = true,
                ph = "Optional JSON object: predicate is exact reported lemma; subject/object are exact surface words. Omit for all roles; {} matches all. Observations only.")])
}
