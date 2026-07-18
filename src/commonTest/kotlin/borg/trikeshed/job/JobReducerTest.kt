package borg.trikeshed.job

import borg.trikeshed.parse.confix.confixDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F0/E0 — JobReducer contract tests.
 *
 * Spec (§F0 RED):
 *  - submit reduces to exactly one Accepted event, one snapshot, and one fact
 *  - Reapplying the same idempotency key produces no second state transition
 *  - The reducer is deterministic: identical frames produce identical snapshots
 *    and CIDs
 *  - expectedRevision acts as optimistic concurrency: stale revisions reject
 *  - Reducer is pure: it does not own coroutines, channels, or open files
 *
 * Anchor: every test below must be re-runnable in any order and produce
 * the same result — no global ordering dependency.
 */
class JobReducerTest {

    private fun frame(
        operation: String,
        jobId: String,
        idempotencyKey: String,
        expectedRevision: Long? = null,
        dependencies: List<String> = emptyList(),
    ): JobFrame {
        val depsStr = if (dependencies.isEmpty()) "" else
            ""","dependencies":["${dependencies.joinToString("\",\"")}"]"""
        val expStr = if (expectedRevision == null) "" else
            ""","expectedRevision":$expectedRevision"""
        val json = """{"operation":"$operation","jobId":"$jobId","idempotencyKey":"$idempotencyKey"$expStr$depsStr}"""
        return JobFrame(confixDoc(json))
    }

    @Test
    fun submitProducesAcceptedEventFactAndSnapshot() {
        val reducer = JobReducer()
        val result = reducer.reduce(frame("submit", "j-1", "ik-1"))

        assertTrue(result.accepted, "submit must be accepted")
        assertNotNull(result.event, "submit must produce an event")
        assertNotNull(result.fact, "submit must produce a fact")
        assertNotNull(result.snapshot, "submit must produce a snapshot")
        assertEquals(JobId.of("j-1"), result.snapshot.jobId)
        assertEquals(1L, result.snapshot.revision, "first accepted frame ⇒ revision 1")
    }

    @Test
    fun duplicateIdempotencyKeyIsRejected() {
        val reducer = JobReducer()
        val f = frame("submit", "j-1", "ik-dup")
        val first = reducer.reduce(f)
        val second = reducer.reduce(f)

        assertTrue(first.accepted, "first apply is accepted")
        assertFalse(second.accepted, "duplicate idempotency key must be rejected")
        assertTrue(second.event is JobEvent.Rejected, "rejection must carry a Rejected event")
        assertTrue((second.event as JobEvent.Rejected).reason.contains("duplicate idempotencyKey"),
            "rejection reason must mention duplicate idempotencyKey")
    }

    @Test
    fun staleExpectedRevisionIsRejected() {
        val reducer = JobReducer()
        // Establish job at revision 1
        reducer.reduce(frame("submit", "j-1", "ik-1"))

        // Client believes job is at revision 0, but it's actually at 1
        val stale = frame("start", "j-1", "ik-2", expectedRevision = 0L)
        val result = reducer.reduce(stale)

        assertFalse(result.accepted, "stale expectedRevision must be rejected")
        assertTrue(result.event is JobEvent.Rejected)
        assertTrue((result.event as JobEvent.Rejected).reason.contains("stale expectedRevision"),
            "rejection must name the cause: stale expectedRevision")
    }

    @Test
    fun currentExpectedRevisionIsAccepted() {
        val reducer = JobReducer()
        reducer.reduce(frame("submit", "j-1", "ik-1"))

        val valid = frame("start", "j-1", "ik-2", expectedRevision = 1L)
        val result = reducer.reduce(valid)
        assertTrue(result.accepted, "frame with matching expectedRevision must be accepted")
        assertEquals(2L, result.snapshot?.revision)
    }

    @Test
    fun sequenceIsMonotonicallyIncreasing() {
        val reducer = JobReducer()
        val a = reducer.reduce(frame("submit", "j-a", "ik-a")).event as JobEvent.Accepted
        val b = reducer.reduce(frame("submit", "j-b", "ik-b")).event as JobEvent.Accepted
        val c = reducer.reduce(frame("submit", "j-c", "ik-c")).event as JobEvent.Accepted

        assertTrue(b.sequence > a.sequence, "sequence must strictly increase across jobs")
        assertTrue(c.sequence > b.sequence)
    }

    @Test
    fun snapshotRevisionAdvancesOnEachAcceptedFrame() {
        val reducer = JobReducer()
        val r1 = reducer.reduce(frame("submit", "j-1", "ik-1"))
        val r2 = reducer.reduce(frame("start", "j-1", "ik-2", expectedRevision = 1L))
        val r3 = reducer.reduce(frame("complete", "j-1", "ik-3", expectedRevision = 2L))

        assertEquals(1L, r1.snapshot?.revision)
        assertEquals(2L, r2.snapshot?.revision)
        assertEquals(3L, r3.snapshot?.revision)
    }

    @Test
    fun factCidIsDeterministicForIdenticalFrame() {
        val reducer = JobReducer()
        val f1 = frame("submit", "j-1", "ik-1")
        val r1 = reducer.reduce(f1)
        val r2 = reducer.reduce(f1)
        // r2 is rejected (duplicate key), so the *first* fact CID is the anchor.
        // Reproduce a parallel reducer with the same logical input to check
        // determinism of the per-frame CID.
        val reducer2 = JobReducer()
        val r3 = reducer2.reduce(f1)

        assertNotNull(r1.fact)
        assertNotNull(r3.fact)
        assertEquals(r1.fact.cid, r3.fact.cid,
            "fact CID must be deterministic for identical logical frame content")
        // r2 is rejected — no second fact produced.
        assertNull(r2.fact, "duplicate idempotency key must not produce a second fact")
    }

    @Test
    fun snapshotLookupReturnsLastAcceptedState() {
        val reducer = JobReducer()
        reducer.reduce(frame("submit", "j-1", "ik-1"))
        val s1 = reducer.snapshot(JobId.of("j-1"))
        assertNotNull(s1)
        assertEquals(1L, s1.revision)

        reducer.reduce(frame("start", "j-1", "ik-2", expectedRevision = 1L))
        val s2 = reducer.snapshot(JobId.of("j-1"))
        assertNotNull(s2)
        assertEquals(2L, s2.revision, "snapshot lookup must return latest revision")
    }

    @Test
    fun unknownJobHasNoSnapshot() {
        val reducer = JobReducer()
        assertNull(reducer.snapshot(JobId.of("never-seen")),
            "unknown job has no snapshot")
        assertEquals(emptyList(), reducer.facts(JobId.of("never-seen")),
            "unknown job has no facts")
    }
}