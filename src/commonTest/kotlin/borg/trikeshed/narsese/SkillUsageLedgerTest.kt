package borg.trikeshed.narsese

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The boundary parse is the whole risk surface here: everything downstream is
 * already tested arithmetic. These pin the shapes Hermes actually writes.
 */
class SkillUsageLedgerTest {

    private val day = 24L * 3600 * 1000

    /** A real row, verbatim in shape from `~/.hermes/skills/.usage.json`. */
    private fun row(
        useCount: Int = 0,
        viewCount: Int = 0,
        lastUsedAt: Any? = null,
        createdAt: String = "2026-08-24T20:17:51.034574+00:00",
        patchCount: Int = 0,
        pinned: Boolean = false,
        state: String = "active",
    ): Map<String, Any?> = mapOf(
        "archived_at" to null,
        "created_at" to createdAt,
        "created_by" to null,
        "last_patched_at" to null,
        "last_reused_patch_generation" to 0,
        "last_used_at" to lastUsedAt,
        "last_viewed_at" to null,
        "patch_count" to patchCount,
        "patch_generation" to 0,
        "pinned" to pinned,
        "state" to state,
        "use_count" to useCount,
        "view_count" to viewCount,
    )

    @Test
    fun parsesIso8601WithMicrosecondsAndOffset() {
        // 2026-08-24T20:17:51Z — checked against the civil-days algorithm.
        val ms = SkillUsageLedger.epochMs("2026-08-24T20:17:51.034574+00:00")
        assertTrue(ms > 0L, "a well-formed instant must parse")

        // Same wall time, +02:00, is two hours EARLIER in UTC.
        val offset = SkillUsageLedger.epochMs("2026-08-24T20:17:51.034574+02:00")
        assertEquals(ms - 2 * 3600 * 1000L, offset, "offset must shift back to UTC")

        // Z and a bare seconds form are both accepted.
        assertEquals(ms, SkillUsageLedger.epochMs("2026-08-24T20:17:51Z"))
    }

    @Test
    fun rejectsMalformedInstantsWithoutGuessing() {
        assertEquals(0L, SkillUsageLedger.epochMs(null))
        assertEquals(0L, SkillUsageLedger.epochMs(""))
        assertEquals(0L, SkillUsageLedger.epochMs("not-a-date"))
        assertEquals(0L, SkillUsageLedger.epochMs("2026-13-01T00:00:00Z"), "month 13 is not a month")
        assertEquals(0L, SkillUsageLedger.epochMs(42), "a number is not an instant")
    }

    @Test
    fun neverUsedSkillAgesFromCreationNotEpochZero() {
        // 69 of the 83 live records have use_count 0 and last_used_at null.
        // Without the created_at fallback every one would read as maximally
        // stale — age would be measured from 1970.
        val r = SkillUsageLedger.record("airtable", row(useCount = 0, lastUsedAt = null))
        assertTrue(r.neverUsed)
        assertEquals(r.createdAtMs, r.usage.lastUsedAtMs, "never-used ages from creation")
        assertTrue(r.usage.lastUsedAtMs > 0L)
    }

    @Test
    fun lastUsedWinsOverCreatedWhenPresent() {
        val r = SkillUsageLedger.record(
            "fork-upstream-sync",
            row(useCount = 3, createdAt = "2026-08-01T00:00:00Z", lastUsedAt = "2026-08-20T00:00:00Z"),
        )
        assertEquals(SkillUsageLedger.epochMs("2026-08-20T00:00:00Z"), r.usage.lastUsedAtMs)
        assertTrue(!r.neverUsed)
    }

    @Test
    fun coercesJsonNumbersAndBoolsFromAnyMinting() {
        // JsonSupport may mint numbers as Double; pinned may arrive as a string.
        val r = SkillUsageLedger.record(
            "x",
            mapOf(
                "use_count" to 7.0,
                "view_count" to "12",
                "patch_count" to 2L,
                "pinned" to "true",
                "state" to "STALE",
                "created_at" to "2026-08-01T00:00:00Z",
            ),
        )
        assertEquals(7, r.usage.useCount)
        assertEquals(12, r.usage.viewCount)
        assertEquals(2, r.usage.patchCount)
        assertTrue(r.usage.pinned)
        assertEquals(CurationState.STALE, r.recorded, "state parse is case-insensitive")
    }

    @Test
    fun skipsNonObjectRowsInsteadOfGuessing() {
        val s = SkillUsageLedger.records(
            mapOf("good" to row(), "junk" to "not-an-object", "alsoJunk" to 3),
        )
        assertEquals(1, s.size)
        assertEquals("good", s[0].name)
    }

    @Test
    fun pinnedSkillNeverDecaysOutOfActive() {
        // durability 1 pins priority — the economy must agree with the clock
        // forever on a pinned skill, which is why divergence can never flag one.
        val r = SkillUsageLedger.record("plan", row(useCount = 5, pinned = true, lastUsedAt = "2026-01-01T00:00:00Z"))
        val far = SkillUsageLedger.epochMs("2026-01-01T00:00:00Z") + 3650 * day
        assertEquals(1f, AttentionEconomy.budgetOf(r.usage, far).df, 1e-6f)
        assertEquals(CurationState.ACTIVE, SkillUsageLedger.modelled(r, far), "pinned never leaves active")
    }

    @Test
    fun divergenceIsEmptyWhenEconomyAgreesWithClock() {
        val fresh = row(useCount = 20, lastUsedAt = "2026-08-24T00:00:00Z", state = "active")
        val records = SkillUsageLedger.records(mapOf("hot" to fresh))
        val now = SkillUsageLedger.epochMs("2026-08-24T00:00:00Z")
        assertEquals(0, SkillUsageLedger.divergent(records, now).size)
    }
}
