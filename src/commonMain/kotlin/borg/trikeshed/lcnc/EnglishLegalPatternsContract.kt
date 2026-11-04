package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

object EnglishLegalPatternsContract {
    const val TYPE = "document.english.patterns"
    fun contract() = LcncPortContract(TYPE, "Inspect legal language candidates", _l["receiptCid", "mode"],
        _l["receiptCid", "inspectionCid", "originalCid", "extractedTextCid", "sentences", "counts", "range", "source", "unresolved"],
        inputKinds = _m["receiptCid" j "id", "mode" j "text"],
        outputKinds = _m["receiptCid" j "id", "inspectionCid" j "id", "originalCid" j "id", "extractedTextCid" j "id",
            "sentences" j "json", "counts" j "json", "range" j "json", "source" j "json", "unresolved" j "json"],
        params = _m["receiptCid" j LcncPortContract.LcncParamSpec(ph = "Retained English inspection CID"),
            "mode" j LcncPortContract.LcncParamSpec(opts = _l["snapshot", "current"],
                ph = "Explicit historical snapshot or checked current source; candidates are not legal conclusions")])
}
