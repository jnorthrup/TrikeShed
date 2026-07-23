package borg.trikeshed.lcnc.reduction

import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.lib.j
import borg.trikeshed.reduction.TrajectoryCarrier
import borg.trikeshed.reduction.TrajectoryOutcome
import borg.trikeshed.reduction.TrajectoryPayload
import borg.trikeshed.reduction.TrajectoryReduction
import borg.trikeshed.reduction.TrajectoryVerdict
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class TrajectoryReductionTest {
    @Test
    fun testTrajectory() {
        try {
            val reduction = TrajectoryReduction()

            // (a) single DrainApplied -> Landed, frozen=false
            val payloadA = TrajectoryPayload(
                title = "Title A",
                headSha = "shaA",
                causes = 1 j { JulesCause.DrainApplied("commit", 0, 1L) },
                depJobIds = listOf("job1"),
                deps = 1 j { JobSnapshot("job1", 1, "k", "complete") }
            )
            val verdictA = reduction.execute(TrajectoryCarrier(payloadA))
            assertEquals(TrajectoryOutcome.Landed, verdictA.outcome)
            assertFalse(verdictA.frozen)
            assertEquals(1, verdictA.attemptCount)
            assertTrue(verdictA.depsSatisfied)

            // (b) three DrainFailed("no patch") -> NoPatch, frozen=true
            val payloadB = TrajectoryPayload(
                title = "Title B",
                headSha = "shaB",
                causes = 3 j { JulesCause.DrainFailed("no patch", 1L) },
                depJobIds = listOf("job1"),
                deps = 1 j { JobSnapshot("job1", 1, "k", "complete") }
            )
            val verdictB = reduction.execute(TrajectoryCarrier(payloadB))
            assertEquals(TrajectoryOutcome.NoPatch, verdictB.outcome)
            assertTrue(verdictB.frozen)
            assertEquals(3, verdictB.attemptCount)

            // (c) two DrainFailed then DrainApplied -> Landed, frozen=false
            val payloadC = TrajectoryPayload(
                title = "Title C",
                headSha = "shaC",
                causes = 3 j { i ->
                    if (i < 2) JulesCause.DrainFailed("failed", 1L)
                    else JulesCause.DrainApplied("commit", 0, 1L)
                },
                depJobIds = listOf("job1"),
                deps = 1 j { JobSnapshot("job1", 1, "k", "complete") }
            )
            val verdictC = reduction.execute(TrajectoryCarrier(payloadC))
            assertEquals(TrajectoryOutcome.Landed, verdictC.outcome)
            assertFalse(verdictC.frozen)
            assertEquals(3, verdictC.attemptCount)

            // (d) deps unsatisfied when a dep jobId has no completed snapshot
            val payloadD = TrajectoryPayload(
                title = "Title D",
                headSha = "shaD",
                causes = 1 j { JulesCause.DrainApplied("commit", 0, 1L) },
                depJobIds = listOf("job1", "job2"),
                deps = 1 j { JobSnapshot("job1", 1, "k", "complete") } // job2 missing!
            )
            val verdictD = reduction.execute(TrajectoryCarrier(payloadD))
            assertFalse(verdictD.depsSatisfied)
        } catch (e: NotImplementedError) {
            fail("Not implemented: \${e.message}")
        }
    }
}
