package modelmux

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ledger walked back: proven limit = the biggest day, spent = today's rows, both charged in the standings. */
class QuotaLegionLedgerTest {
    private val day = QuotaLegion.DAY_MS
    private val now = 20L * day + 6 * 3_600_000L // some day, 06:00

    private val rows = listOf(
        LedgerRow("zai", "glm-5.3-flash", 600_000, 90_000, now - 2 * 3_600_000L),   // today: 690k
        LedgerRow("zai", "glm-5.3-flash", 60_000, 24_000, now - 3 * 3_600_000L),    // today: 84k
        LedgerRow("zai", "glm-5.3", 1_000_000, 255_094, now - 3 * day),            // three days ago: 1,255,094 (the proven day)
        LedgerRow("nvidia", "nemotron", 400_000, 15_051, now - 2 * day),
        LedgerRow("", "orphan", 5, 5, now),                                         // no provider: skipped
    )

    @Test
    fun walkBackFindsTheProvenDayAndTodaysSpend() {
        val q = QuotaLegion.walkBack(rows, now)
        assertEquals(setOf("nvidia", "zai"), q.keys)
        assertEquals(1_255_094L, q.getValue("zai").provenLimit, "the biggest single day is the proven floor")
        assertEquals(774_000L, q.getValue("zai").spentThisWindow, "both of today's rows are already gone")
        assertEquals(415_051L, q.getValue("nvidia").provenLimit)
        assertEquals(0L, q.getValue("nvidia").spentThisWindow, "nvidia's rows are two days old")
    }

    @Test
    fun standingsChargeTheLedgerUntilTheWindowRolls() {
        val legion = QuotaLegion.fromLedger(rows, now)
        val reactor = borg.trikeshed.userspace.reactor.MuxReactorElement()
        reactor.recordAccess("llm.zai.key", "zai")
        reactor.recordAccess("llm.nvidia.key", "nvidia")
        reactor.recordAccess("llm.groq.key", "groq")
        fun row(nowMs: Long, provider: String): QuotaStanding {
            val s = legion.standings(reactor.flowState.value, nowMs)
            return (0 until s.size).map { s[it] }.first { it.provider == provider }
        }
        val zai = row(now, "zai"); val nvidia = row(now, "nvidia"); val groq = row(now, "groq")
        assertEquals(1_255_094L, zai.limit); assertEquals(774_000L, zai.spent); assertTrue(zai.isUsable)
        assertEquals(481_094L, zai.remaining, "remaining is proven minus what Hermes already burned today")
        assertEquals(0L, nvidia.spent); assertEquals(415_051L, nvidia.limit)
        assertEquals(0L, groq.limit, "a provider the ledger never reached stays unmetered"); assertTrue(groq.isUsable)

        // This process's own receipt lands on top of the ledger's charge.
        legion.applyReceipt("llm.zai.key", "zai", receipt(inTok = 400_000, outTok = 90_000), now)
        val zai2 = row(now, "zai")
        assertEquals(1_264_000L, zai2.spent); assertFalse(zai2.isUsable, "over the proven day: benched until the window rolls")

        // Tomorrow the ledger's charge has expired and only fresh receipts count.
        val zai3 = row(now + day, "zai")
        assertEquals(0L, zai3.spent); assertTrue(zai3.isUsable)
    }

    private fun receipt(inTok: Int, outTok: Int) = borg.trikeshed.modelmux.ModelResponseReceipt(
        receiptId = "mrec-test", modelId = "glm-5.3-flash", providerId = "zai", requestHash = "sha256:0",
        action = "chat", httpStatus = 200, latencyMs = 1L, inputTokens = inTok, outputTokens = outTok,
        cachedHit = false, capturedAt = now,
    )
}
