package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** A report over one verified inspection; no rule, admission or source-authentication capability. */
object EnglishNalContract {
    const val TYPE = "document.english.nal"
    fun contract() = LcncPortContract(TYPE, "Inspect retained English NAL hypotheses",
        _l["receiptCid", "mode"], _l["report", "frameCid", "inspectionCid", "status", "source", "passages"],
        inputKinds = _m["receiptCid" j "id", "mode" j "text"],
        outputKinds = _m["report" j "json", "frameCid" j "id", "inspectionCid" j "id", "status" j "text",
            "source" j "json", "passages" j "json"],
        params = _m["receiptCid" j LcncPortContract.LcncParamSpec(ph = "Retained English inspection CID; no text or parser fallback"),
            "mode" j LcncPortContract.LcncParamSpec(opts = _l["snapshot", "current"],
                ph = "Explicit historical snapshot or checked current source; no review or admission")])
}
