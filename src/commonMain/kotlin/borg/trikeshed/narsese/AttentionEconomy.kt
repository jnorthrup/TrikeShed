package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import kotlin.math.exp
import kotlin.math.pow

/**
 * Curation lifecycle states as priority-floor crossings. Hermes' curator step-1
 * clock transitions (active→stale at 30d, →archived at 90d) become threshold
 * crossings of the decayed priority — one mechanism for skills, memory beliefs,
 * and tool-use beliefs.
 */
enum class CurationState(val floor: Float) {
    ACTIVE(0.25f),
    STALE(0.0125f),
    ARCHIVED(0f),
}

/**
 * AttentionEconomy — pure budget arithmetic; this IS Hermes curator step 1,
 * re-based onto NARS budgets. Priority decays, durability dampens the decay,
 * quality and evidence are untouched (the invariant: evidence never decays,
 * only attention does).
 *
 * Tuning: with the daemon's daily DecayTick, an unpinned baseline skill
 * (durability 0.3 ⇒ effective daily factor 0.3 + 0.7·λ ≈ 0.955) starting from
 * p=1 crosses the ACTIVE floor (0.25) near day 30 and the STALE floor (0.0125,
 * = ARCHIVED entry = bag eviction/CAS spill) near day 95 — the Hermes 30/90
 * schedule as floor crossings of one exponential.
 */
object AttentionEconomy {

    /** Recency half-life for the priority term. */
    const val HALF_LIFE_MS: Long = 14L * 24 * 3600 * 1000

    /** Per-DecayTick decay factor (daily tick): p' = p·(d + (1−d)·λ). */
    const val LAMBDA: Float = 0.9354f

    /**
     * Budget from Hermes-shaped usage activity plus review verdicts.
     * priority = recency half-life blend + saturating use count;
     * durability = pinned / patch-generation earned;
     * quality = review-verdict truth expectation.
     */
    fun budgetOf(u: SkillUsage, nowMs: Long, verdicts: EvidenceCoord = EvidenceCoord.EMPTY): BudgetCoord {
        val age = (nowMs - u.lastUsedAtMs).coerceAtLeast(0L).toDouble()
        val recency = 2.0.pow(-age / HALF_LIFE_MS).toFloat()
        val use = 1f - exp(-u.useCount / 8f)
        val p = (0.6f * recency + 0.4f * use).coerceIn(0f, 1f)
        val d = if (u.pinned) 1f else (0.3f + 0.1f * u.patchCount).coerceAtMost(0.9f)
        val q = if (verdicts.total == 0L) 0.5f else Nal.truthOf(verdicts).expectation()
        return BudgetCoord(p, d, q)
    }

    /** NARS forget: only priority moves; durability dampens; pinned (d=1) never decays. */
    fun decay(b: BudgetCoord, lambda: Float = LAMBDA): BudgetCoord =
        BudgetCoord(b.pf * (b.df + (1f - b.df) * lambda), b.df, b.qf)

    fun stateOf(b: BudgetCoord): CurationState = when {
        b.pf >= CurationState.ACTIVE.floor -> CurationState.ACTIVE
        b.pf >= CurationState.STALE.floor -> CurationState.STALE
        else -> CurationState.ARCHIVED
    }
}

/**
 * Boundary map of Hermes' `.usage.json` per-skill record — never the in-process
 * shape; parse at the edge, discard the format.
 */
data class SkillUsage(
    val useCount: Int = 0,
    val viewCount: Int = 0,
    val lastUsedAtMs: Long = 0L,
    val patchCount: Int = 0,
    val pinned: Boolean = false,
)
