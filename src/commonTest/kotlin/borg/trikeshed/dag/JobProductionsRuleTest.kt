package borg.trikeshed.dag

import borg.trikeshed.job.ContentId
import borg.trikeshed.cursor.blackboardContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Section 7 RED — Production rules tied to Kanban columns.
 *
 * The plan defines specific rule outcomes and their durable records:
 *   - Dependency set satisfied → activation + Ready command → card enters Ready
 *   - Dependency failed → activation + Block event → Attention
 *   - Lease allocated → activation + attempt snapshot → Active Work
 *   - WIP limit reached → rejected start + explanation → card stays Ready
 *   - Attempt failed → failure frame + retraction → Attention
 *   - Retry admitted → new AttemptId, prior link → same card, history retained
 *   - All validations succeeded → terminal facts + Complete → Closed
 *   - Supporting fact retracted → TMS retraction → status disappears
 */
class JobProductionsRuleTest {

    @Test
    fun dependencySatisfiedEmitsReadyCommand() {
        val prod = JobProductions.dependencySatisfied
        assertEquals("dep-satisfied", prod.ruleId)
        assertTrue(prod.salience > 0, "dep-satisfied must have positive salience")

        // Fire on: all dependencies closed
        val activation = prod.tryActivate(
            jobId = "j-1",
            board = blackboardContext("board-a"),
            dependencies = listOf("dep-1"),
            dependencyStatuses = mapOf("dep-1" to "closed"),
        )
        assertNotNull(activation)
        assertEquals("ready", activation!!.action.fields["status"])
    }

    @Test
    fun dependencyFailedEmitsBlockCommand() {
        val prod = JobProductions.dependencyFailed
        val activation = prod.tryActivate(
            jobId = "j-1",
            board = blackboardContext("board-a"),
            dependencies = listOf("dep-1"),
            dependencyStatuses = mapOf("dep-1" to "failed"),
        )
        assertNotNull(activation)
        assertEquals("blocked", activation!!.action.fields["status"])
    }

    @Test
    fun wipLimitReachedBlocksAdmission() {
        val prod = JobProductions.wipLimitCheck
        val activation = prod.tryActivate(
            jobId = "j-1",
            board = blackboardContext("board-a"),
            currentWipCount = 10,
            wipLimit = 10,
        )
        assertNotNull(activation)
        assertEquals("block-admission", activation!!.action.fields["status"])
    }

    @Test
    fun leaseExpiredEmitsReclaimCommand() {
        val prod = JobProductions.leaseExpired
        val activation = prod.tryActivate(
            jobId = "j-1",
            board = blackboardContext("board-a"),
            leaseExpiryMs = 1000L,
            nowMs = 2000L,
        )
        assertNotNull(activation)
        assertEquals("reclaim", activation!!.action.fields["status"])
    }

    private fun assertNotNull(v: Any?) {
        kotlin.test.assertNotNull(v)
    }
}
