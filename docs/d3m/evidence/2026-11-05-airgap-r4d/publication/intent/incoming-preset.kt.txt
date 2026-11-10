package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/** Offered wiring over the retained-input owner; no parser, live bag, review or engine selection. */
object EnglishNalPreset {
    const val NAME = "preset-english-nal-report"
    val info = LcncPresetInfo(NAME, "Inspect retained English NAL hypotheses",
        "Evaluates one complete retained English atomic-symbol frame with the existing bounded one-level NAL experiment.",
        "An exact retained English inspection CID and explicit current or historical source mode. No parser or provider is called.",
        "The full report, dependent source basis and every premise occurrence. One selected quotation is source context, not the whole derivation basis.",
        "Set receiptCid and mode, then Run explicitly. Change the passage path to inspect another occurrence; numeric confidence is NAL evidence, not parser probability.")

    fun build(receiptCid: String = "", mode: String = "snapshot"): LcncProgram {
        if (receiptCid.isNotEmpty()) ContentId(receiptCid)
        require(mode in _s["snapshot", "current"]) { "Choose explicit current or snapshot English source mode" }
        return LcncProgram(NAME, s_[
            LcncNode("instructions", "note", _m["text" j
                "Choose a complete retained English inspection and explicit source mode, then Run. EVALUATED is an experiment status, not theorem support or legal truth. Deduction and weak induction keep dependent source ancestry; repeated occurrences are not independent units. The full report and all passages remain outputs. A computed frame CID alone is not the saved report; inspect retained run outputs. The selected passage is ONE source occurrence, not the complete proof basis. No parser, provider, rule admission, live bag or review is wired."],
                x = 40.0, y = 20.0, width = 670.0, height = 190.0),
            LcncNode("receipt", LcncContracts.SCOPE_IN, _m["name" j "receiptCid", "kind" j "id", "default" j receiptCid], x = 40.0, y = 250.0),
            LcncNode("mode", "text.value", _m["value" j mode], x = 40.0, y = 450.0),
            LcncNode("evaluate", EnglishNalContract.TYPE, x = 400.0, y = 270.0),
            LcncNode("report", "display", x = 780.0, y = 160.0),
            LcncNode("passage", "confix.pickPath", _m["path" j "0", "geometry" j "kotlin"], x = 780.0, y = 500.0),
            LcncNode("source", "display", x = 1140.0, y = 500.0),
            LcncNode("report-out", LcncContracts.SCOPE_OUT, _m["name" j "report", "kind" j "json"], x = 1140.0, y = 160.0),
            LcncNode("passages-out", LcncContracts.SCOPE_OUT, _m["name" j "passages", "kind" j "json"], x = 1500.0, y = 160.0),
            LcncNode("frame-out", LcncContracts.SCOPE_OUT, _m["name" j "frameCid", "kind" j "id"], x = 1500.0, y = 370.0),
            LcncNode("source-out", LcncContracts.SCOPE_OUT, _m["name" j "source", "kind" j "json"], x = 1500.0, y = 580.0),
        ], s_[
            LcncWire("receipt", "value", "evaluate", "receiptCid"), LcncWire("mode", "value", "evaluate", "mode"),
            LcncWire("evaluate", "report", "report", "x"), LcncWire("evaluate", "passages", "passage", "json?"),
            LcncWire("evaluate", "source", "source", "x"), LcncWire("evaluate", "report", "report-out", "value"),
            LcncWire("evaluate", "passages", "passages-out", "value"), LcncWire("evaluate", "frameCid", "frame-out", "value"),
            LcncWire("evaluate", "source", "source-out", "value"),
        ], view = LcncView(x = 20.0, y = 20.0, zoom = 0.65), seq = 12)
    }
}
