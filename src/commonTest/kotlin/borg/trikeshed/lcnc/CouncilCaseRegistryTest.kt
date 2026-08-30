package borg.trikeshed.lcnc

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CouncilCaseRegistry gate — the per-case nexus the council records through.
 *
 *  - THE SINGLETON REGRESSION: the legacy tribunal's one boot instance meant
 *    the Nth case's verdict was never as recorded as the first. Here every
 *    case gets its own nexus: case-a AND case-b both land terminal.
 *  - Idempotency: the same (caseId, verdictCid) recorded twice returns the
 *    identical snapshot cid without advancing the job's revision again.
 *  - Mistrial rides the `fail` operation to lifecycle `failed` — loud on the
 *    record, never a silent empty ruling.
 *  - Concurrency: parallel recorders of the same verdict serialize on the
 *    per-case mutex; exactly one advance commits.
 *  - Cap: close-after-commit — oldest terminal entries are evicted past the
 *    cap, but their recorded cid still answers from cache.
 */
class CouncilCaseRegistryTest {

    private val plan = TribunalInstance.schemaPlan()

    @Test
    fun everyCaseRecordsItsOwnRuling_theSingletonRegression() = runTest {
        val registry = CouncilCaseRegistry(this, plan)
        try {
            val cidA = registry.recordRuling("case-a", "sha256:" + "a".repeat(64), "sha256:" + "1".repeat(64))
            val cidB = registry.recordRuling("case-b", "sha256:" + "b".repeat(64), "sha256:" + "2".repeat(64))

            // BOTH cases return content-addressed snapshot cids — the Nth
            // verdict is as recorded as the first.
            assertTrue(cidA.isNotBlank(), "case-a must return a non-blank snapshot cid")
            assertTrue(cidB.isNotBlank(), "case-b must return a non-blank snapshot cid")
            assertTrue(cidA.startsWith("sha256:"), "case-a cid must be content-addressed: $cidA")
            assertTrue(cidB.startsWith("sha256:"), "case-b cid must be content-addressed: $cidB")

            // Both lifecycles are terminal (complete → closed), independently.
            assertEquals("closed", registry.lifecycle("case-a"))
            assertEquals("closed", registry.lifecycle("case-b"))
            assertEquals(2, registry.liveCases())
        } finally {
            registry.close()
        }
    }

    @Test
    fun sameVerdictRecordedTwiceIsIdempotent() = runTest {
        val registry = CouncilCaseRegistry(this, plan)
        try {
            val verdictCid = "sha256:" + "c".repeat(64)
            val first = registry.recordRuling("case-a", verdictCid, "sha256:" + "3".repeat(64))
            val revisionAfterFirst = registry.revision("case-a")
            assertNotNull(revisionAfterFirst)

            val second = registry.recordRuling("case-a", verdictCid, "sha256:" + "3".repeat(64))
            assertEquals(first, second, "a repeat record must return the identical cached cid")
            assertEquals(
                revisionAfterFirst, registry.revision("case-a"),
                "an idempotent repeat must NOT advance the job's revision",
            )
        } finally {
            registry.close()
        }
    }

    @Test
    fun mistrialLandsTheFailedLifecycle() = runTest {
        val registry = CouncilCaseRegistry(this, plan)
        try {
            val cid = registry.recordMistrial("case-m", "all 34 seats refused")
            assertTrue(cid.isNotBlank(), "mistrial must still return a snapshot cid")
            assertEquals("failed", registry.lifecycle("case-m"), "mistrial rides the fail operation to 'failed'")
        } finally {
            registry.close()
        }
    }

    @Test
    fun parallelRecordersOfTheSameVerdictAdvanceExactlyOnce() = runTest {
        val registry = CouncilCaseRegistry(this, plan)
        try {
            val verdictCid = "sha256:" + "d".repeat(64)
            val transcriptCid = "sha256:" + "4".repeat(64)
            // Two parallel recorders: the per-case mutex serializes them, the
            // idempotency cache answers the loser — no exception, one advance.
            val cids = listOf(
                async { registry.recordRuling("case-c", verdictCid, transcriptCid) },
                async { registry.recordRuling("case-c", verdictCid, transcriptCid) },
            ).awaitAll()
            assertEquals(cids[0], cids[1], "both parallel recorders must observe the one committed cid")
            // Exactly one terminal advance: submit(1) → start(2) → complete(3).
            assertEquals(3L, registry.revision("case-c"), "exactly one terminal advance must have committed")
            assertEquals("closed", registry.lifecycle("case-c"))
        } finally {
            registry.close()
        }
    }

    @Test
    fun capEvictsOldestTerminalButKeepsTheRecordedCid() = runTest {
        val registry = CouncilCaseRegistry(this, plan, cap = 2)
        try {
            val cidD = registry.recordRuling("case-d", "sha256:" + "e".repeat(64), "sha256:" + "5".repeat(64))
            registry.recordRuling("case-e", "sha256:" + "f".repeat(64), "sha256:" + "6".repeat(64))
            registry.recordRuling("case-f", "sha256:" + "0".repeat(64), "sha256:" + "7".repeat(64))

            assertEquals(2, registry.liveCases(), "the third terminal case must evict the oldest past cap=2")
            // The evicted (oldest terminal) case still answers from cache.
            assertEquals(cidD, registry.recordedCid("case-d"), "an evicted case's recorded cid must answer from cache")
        } finally {
            registry.close()
        }
    }

    @Test
    fun aTerminalCaseIsClosedForNewCommits_loudly() = runTest {
        val registry = CouncilCaseRegistry(this, plan)
        try {
            registry.recordRuling("case-x", "sha256:" + "9".repeat(64), "sha256:" + "8".repeat(64))
            // A DIFFERENT verdict on a terminal case is refused with the case named.
            val thrown = assertFailsWith<IllegalStateException> {
                registry.recordRuling("case-x", "sha256:" + "7".repeat(64), "sha256:" + "8".repeat(64))
            }
            assertTrue("case-x" in (thrown.message ?: ""), "the refusal must name the case: ${thrown.message}")
        } finally {
            registry.close()
        }
    }
}
