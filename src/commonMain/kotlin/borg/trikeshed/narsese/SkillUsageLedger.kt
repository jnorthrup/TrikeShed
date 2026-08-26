package borg.trikeshed.narsese

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * SkillUsageLedger — pure projection of Hermes' `.usage.json` into the shapes
 * [AttentionEconomy] already speaks.
 *
 * Hermes decides curation by wall clock: `now - last_activity > 30d` marks a
 * skill stale, `> 90d` archives it. [AttentionEconomy] re-bases that same
 * schedule as floor crossings of one decaying priority. This file is the only
 * thing standing between the two — the boundary parse that turns a JSON record
 * into [SkillUsage] so the economy can read the same evidence the clock reads.
 *
 * Parse at the edge, discard the format: nothing downstream sees a Map again.
 */
data class SkillUsageRecord(
    val name: String,
    val usage: SkillUsage,
    /** `state` as Hermes recorded it — the clock's verdict, for comparison. */
    val recorded: CurationState,
    /**
     * Never invoked. Hermes exempts these from auto-transition while young
     * (`use_count == 0` and younger than the stale cutoff), so a divergence on
     * a never-used skill is the exemption talking, not a disagreement.
     */
    val neverUsed: Boolean,
    /** Epoch ms the record was created; the age fallback when never used. */
    val createdAtMs: Long,
)

object SkillUsageLedger {

    /**
     * Project a parsed `.usage.json` document (skill name -> record) into
     * [SkillUsageRecord]s. Rows that are not objects are skipped, never guessed.
     * Ordering follows the document so runs are reproducible.
     */
    fun records(raw: Map<String, Any?>): Series<SkillUsageRecord> {
        val out = ArrayList<SkillUsageRecord>(raw.size)
        for ((name, value) in raw) {
            val row = value as? Map<*, *> ?: continue
            out.add(record(name, row))
        }
        return out.size j { out[it] }
    }

    /** Project one `.usage.json` row. */
    fun record(name: String, row: Map<*, *>): SkillUsageRecord {
        val useCount = int(row["use_count"])
        val createdAtMs = epochMs(row["created_at"])
        // Hermes ages a never-used skill from its creation, not from epoch 0 —
        // without this fallback every fresh skill reads as maximally stale.
        val lastUsedAtMs = epochMs(row["last_used_at"])
            .takeIf { it > 0L }
            ?: epochMs(row["last_viewed_at"]).takeIf { it > 0L }
            ?: createdAtMs
        return SkillUsageRecord(
            name = name,
            usage = SkillUsage(
                useCount = useCount,
                viewCount = int(row["view_count"]),
                lastUsedAtMs = lastUsedAtMs,
                patchCount = int(row["patch_count"]),
                pinned = bool(row["pinned"]),
            ),
            recorded = curationState(row["state"]),
            neverUsed = useCount == 0,
            createdAtMs = createdAtMs,
        )
    }

    /**
     * The economy's reading of the same evidence at [nowMs].
     *
     * Pinned short-circuits to ACTIVE. [AttentionEconomy.budgetOf] derives
     * priority from recency and use directly, while durability only dampens
     * [AttentionEconomy.decay] — so an unguarded read lets a pinned-but-idle
     * skill fall to STALE, which is exactly the transition Hermes exempts
     * pinned skills from. The pin is a floor on state, not merely on decay rate.
     */
    fun modelled(
        r: SkillUsageRecord,
        nowMs: Long,
        verdicts: EvidenceCoord = EvidenceCoord.EMPTY,
    ): CurationState =
        if (r.usage.pinned) CurationState.ACTIVE
        else AttentionEconomy.stateOf(AttentionEconomy.budgetOf(r.usage, nowMs, verdicts))

    /**
     * Records where the economy and the clock disagree. A pinned skill never
     * diverges (durability 1 pins priority); a never-used young skill diverges
     * only because Hermes exempts it, which [SkillUsageRecord.neverUsed] flags.
     */
    fun divergent(records: Series<SkillUsageRecord>, nowMs: Long): Series<SkillUsageRecord> {
        val out = ArrayList<SkillUsageRecord>()
        for (i in 0 until records.size) {
            val r = records[i]
            if (modelled(r, nowMs) != r.recorded) out.add(r)
        }
        return out.size j { out[it] }
    }

    // ── boundary coercions ────────────────────────────────────────────────
    // JSON numbers arrive as whatever the parser minted; never assume Int.

    private fun int(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    private fun bool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        is Number -> v.toInt() != 0
        else -> false
    }

    private fun curationState(v: Any?): CurationState = when ((v as? String)?.lowercase()) {
        "stale" -> CurationState.STALE
        "archived" -> CurationState.ARCHIVED
        else -> CurationState.ACTIVE
    }

    /**
     * ISO-8601 instant -> epoch ms; 0 when absent or unparseable.
     *
     * Hand-rolled rather than pulling a datetime dependency into the parse:
     * Hermes writes exactly `YYYY-MM-DDTHH:MM:SS[.ffffff][+HH:MM|Z]`, and the
     * fields the economy needs are day-scale — sub-second precision and the
     * offset are read but never load-bearing.
     */
    internal fun epochMs(v: Any?): Long {
        val s = (v as? String)?.trim()?.takeIf { it.length >= 19 } ?: return 0L
        val y = s.substring(0, 4).toIntOrNull() ?: return 0L
        val mo = s.substring(5, 7).toIntOrNull() ?: return 0L
        val d = s.substring(8, 10).toIntOrNull() ?: return 0L
        if (s[10] != 'T' && s[10] != ' ') return 0L
        val h = s.substring(11, 13).toIntOrNull() ?: return 0L
        val mi = s.substring(14, 16).toIntOrNull() ?: return 0L
        val sec = s.substring(17, 19).toIntOrNull() ?: return 0L
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || sec !in 0..60) return 0L

        val days = daysFromCivil(y, mo, d)
        var ms = ((days * 86_400L) + h * 3600L + mi * 60L + sec) * 1000L

        // Trailing offset: Z (or absent) is UTC; ±HH:MM shifts back to UTC.
        val tail = s.substring(19)
        val signIdx = tail.indexOfLast { it == '+' || it == '-' }
        if (signIdx >= 0 && tail.length >= signIdx + 6) {
            val oh = tail.substring(signIdx + 1, signIdx + 3).toIntOrNull()
            val om = tail.substring(signIdx + 4, signIdx + 6).toIntOrNull()
            if (oh != null && om != null) {
                val offset = (oh * 3600L + om * 60L) * 1000L
                ms += if (tail[signIdx] == '+') -offset else offset
            }
        }
        return ms
    }

    /** Days since 1970-01-01 — Howard Hinnant's civil-from-days, inverted. */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = (y - era * 400).toLong()                       // [0, 399]
        val doy = (153L * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097L + doe - 719_468L
    }
}
