package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase-3 gate: decay is priority-only and monotone; floor schedule matches Hermes 30/90. */
class AttentionEconomyTest {

    @Test
    fun decayTouchesOnlyPriority() {
        val b = BudgetCoord(0.8f, 0.4f, 0.7f)
        val d = AttentionEconomy.decay(b)
        assertTrue(d.pf < b.pf)
        assertEquals(b.df, d.df, 1e-4f)
        assertEquals(b.qf, d.qf, 1e-4f)
    }

    @Test
    fun pinnedNeverDecays() {
        val pinned = BudgetCoord(0.9f, 1.0f, 0.5f)
        var b = pinned
        repeat(365) { b = AttentionEconomy.decay(b) }
        assertEquals(0.9f, b.pf, 1e-3f, "durability 1.0 must fully damp decay")
        assertEquals(CurationState.ACTIVE, AttentionEconomy.stateOf(b))
    }

    @Test
    fun decayIsMonotoneInPriority() {
        var lo = BudgetCoord(0.3f, 0.3f, 0.5f)
        var hi = BudgetCoord(0.9f, 0.3f, 0.5f)
        repeat(50) {
            lo = AttentionEconomy.decay(lo)
            hi = AttentionEconomy.decay(hi)
            assertTrue(hi.pf >= lo.pf)
        }
    }

    @Test
    fun floorScheduleMatchesHermes30And90() {
        // baseline unpinned skill: durability 0.3, start at full priority, daily ticks
        var b = BudgetCoord(1.0f, 0.3f, 0.5f)
        var dayStale = -1
        var dayArchived = -1
        for (day in 1..150) {
            b = AttentionEconomy.decay(b)
            if (dayStale < 0 && AttentionEconomy.stateOf(b) != CurationState.ACTIVE) dayStale = day
            if (dayArchived < 0 && AttentionEconomy.stateOf(b) == CurationState.ARCHIVED) dayArchived = day
        }
        assertTrue(dayStale in 27..33, "ACTIVE→STALE should cross near day 30, got $dayStale")
        assertTrue(dayArchived in 85..105, "STALE→ARCHIVED should cross near day 95, got $dayArchived")
    }

    @Test
    fun budgetOfMapsUsage() {
        val now = 1_000_000_000_000L
        val fresh = AttentionEconomy.budgetOf(SkillUsage(useCount = 20, lastUsedAtMs = now), now)
        val stale = AttentionEconomy.budgetOf(SkillUsage(useCount = 20, lastUsedAtMs = now - 60L * 24 * 3600 * 1000), now)
        assertTrue(fresh.pf > stale.pf, "recency must raise priority")
        val pinned = AttentionEconomy.budgetOf(SkillUsage(pinned = true), now)
        assertEquals(1.0f, pinned.df, 1e-4f)
        val patched = AttentionEconomy.budgetOf(SkillUsage(patchCount = 4), now)
        assertEquals(0.7f, patched.df, 1e-3f)
        val vouched = AttentionEconomy.budgetOf(SkillUsage(), now, verdicts = EvidenceCoord(10 * Nal.UNIT, 0))
        assertTrue(vouched.qf > 0.8f, "positive verdicts must raise quality")
    }
}
