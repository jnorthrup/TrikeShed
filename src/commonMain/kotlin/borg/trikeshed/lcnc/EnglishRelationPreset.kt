package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

object EnglishRelationPreset {
    const val NAME = "preset-english-relation-roles"
    val info = LcncPresetInfo(NAME, "Inspect ordered English relation roles",
        "Queries direct subject, verb and object dependencies from one verified retained inspection.",
        "An exact inspection CID and explicit current or historical source mode. No parser or provider executes.",
        "Typed receipt-local quad bindings, visible exclusions, unresolved guard observations and exact source quotations. These are syntax observations, not positive assertions or evidence votes.",
        "Set receiptCid and mode, optionally edit query with exact predicate lemma and subject/object words. Run, then choose a passage path to focus one matching source occurrence.")
    fun build(receiptCid: String = "", mode: String = "snapshot", query: String = "{}"): LcncProgram {
        if (receiptCid.isNotEmpty()) ContentId(receiptCid)
        require(mode in _s["snapshot", "current"])
        borg.trikeshed.rdf.EnglishRelationFrames.request(query)
        return LcncProgram(NAME, s_[
            LcncNode("instructions", "note", _m["text" j "Set a retained inspection CID and source mode. Exact predicate lemma, subject and object filters select ordered parser roles. Guards and negation remain unresolved. Exclusions remain in the full report. Counts are not evidence. Selected passage is one occurrence; inspect its full dependencies and qualifications in the report."], x = 40.0, y = 20.0, width = 680.0, height = 170.0),
            LcncNode("receipt", LcncContracts.SCOPE_IN, _m["name" j "receiptCid", "kind" j "id", "default" j receiptCid], x = 40.0, y = 240.0),
            LcncNode("mode", "text.value", _m["value" j mode], x = 40.0, y = 420.0),
            LcncNode("relations", EnglishRelationContract.TYPE, _m["query" j query], x = 400.0, y = 250.0),
            LcncNode("report", "display", x = 780.0, y = 160.0),
            LcncNode("matches", "display", x = 780.0, y = 340.0),
            LcncNode("passage", "confix.pickPath", _m["path" j "0", "geometry" j "kotlin"], x = 780.0, y = 520.0),
            LcncNode("source", "display", x = 1140.0, y = 520.0),
            LcncNode("report-out", LcncContracts.SCOPE_OUT, _m["name" j "report", "kind" j "json"], x = 1140.0, y = 160.0),
            LcncNode("matches-out", LcncContracts.SCOPE_OUT, _m["name" j "matches", "kind" j "json"], x = 1140.0, y = 340.0),
            LcncNode("passages-out", LcncContracts.SCOPE_OUT, _m["name" j "passages", "kind" j "json"], x = 1500.0, y = 340.0),
            LcncNode("source-out", LcncContracts.SCOPE_OUT, _m["name" j "source", "kind" j "json"], x = 1500.0, y = 520.0),
        ], s_[
            LcncWire("receipt", "value", "relations", "receiptCid"), LcncWire("mode", "value", "relations", "mode"),
            LcncWire("relations", "report", "report", "x"), LcncWire("relations", "matches", "matches", "x"),
            LcncWire("relations", "passages", "passage", "json?"), LcncWire("passage", "value", "source", "x"),
            LcncWire("relations", "report", "report-out", "value"), LcncWire("relations", "matches", "matches-out", "value"),
            LcncWire("relations", "passages", "passages-out", "value"), LcncWire("relations", "source", "source-out", "value"),
        ], view = LcncView(x = 20.0, y = 20.0, zoom = 0.65), seq = 12)
    }
}
