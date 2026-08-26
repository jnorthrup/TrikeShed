package borg.trikeshed.narsese

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * CuratorLedger — pure projection of a hermes curator ledger (JSONL) into
 * [CuratorImpulse]s. One ledger record IS one curation action the curator
 * proposed; the evidence session is the transcript a hindsight replay
 * would score it against.
 *
 * Shapes honoured (both observed in the wild):
 *  - `{"action":"create","skill":"…","evidence":{"session_id":"…"}}`
 *  - `{"action":"patch"|"write_file","skill":"…","evidence":{"session_id":"…","file_path":"…"}}`
 *
 * Unknown actions map to PATCH (the conservative reading: the curator
 * touched an existing artifact). Records without a skill name are dropped —
 * an impulse with no subject is not an impulse.
 */
object CuratorLedger {

    /** Ledger action → impulse kind. Conservative by design. */
    fun kindOf(action: String?): CuratorImpulseKind = when (action) {
        "create" -> CuratorImpulseKind.CREATE
        "adopt" -> CuratorImpulseKind.ADOPT
        "prune" -> CuratorImpulseKind.PRUNE
        "consolidate" -> CuratorImpulseKind.CONSOLIDATE
        else -> CuratorImpulseKind.PATCH // patch | write_file | unknown
    }

    /**
     * Project parsed ledger records into impulses. Each record is the JSON
     * `Map<String,Any?>` a JSON parser hands back; only `action`, `skill`,
     * `evidence.session_id`, and `id` are read — everything else is ignored,
     * never guessed.
     */
    fun impulses(records: Series<Map<String, Any?>>): Series<CuratorImpulse> {
        if (records.size == 0) return emptySeriesOf()
        val out = ArrayList<CuratorImpulse>(records.size)
        for (i in 0 until records.size) {
            val r = records[i] ?: continue
            val skill = r["skill"]?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
            val evidence = r["evidence"] as? Map<*, *>
            out.add(
                CuratorImpulse(
                    kind = kindOf(r["action"]?.toString()),
                    subject = skill,
                    rationale = r["action"]?.toString() ?: "unknown",
                    proposalCid = (evidence?.get("session_id"))?.toString(),
                ),
            )
        }
        return out.toSeries()
    }

    /**
     * The replay target set: every (impulse subject → evidence session id)
     * pair the ledger names, as the caller's join spec for transcript
     * retrieval. Destructure: `val (subject, sessionId) = pair`.
     */
    fun replayTargets(impulses: Series<CuratorImpulse>): Series<Join<String, String>> {
        if (impulses.size == 0) return emptySeriesOf()
        return impulses.size j { i: Int ->
            val imp = impulses[i]
            imp.subject j (imp.proposalCid ?: "")
        }
    }
}
