package borg.trikeshed.kanban

import borg.trikeshed.job.CasStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Claimed work passes review first. A card owned by `claim:*` cannot go
 * RUNNING → DONE, cannot be `complete`d by the lifecycle verb, and leaves
 * REVIEW for DONE only under a mover who is not the claimant.
 */
class BoardClaimGuardTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "claim-guard-$name-${System.nanoTime()}").apply { mkdirs() }

    private suspend fun send(el: BoardStoreElement, vararg pairs: Pair<String, Any?>): BoardApply {
        val d = CompletableDeferred<BoardApply>()
        el.intake.send(BoardIntake(mapOf(*pairs), d))
        return d.await()
    }

    private suspend fun claimed(el: BoardStoreElement, job: String): Long {
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to job, "idempotencyKey" to "s-$job", "title" to "Card $job"))
        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to job, "idempotencyKey" to "r-$job", "expectedRevision" to 1, "toColumn" to "ready"))
        // the claim: a Move carrying owner re-owns the card (advanceRow reads raw["owner"] on any command)
        val run = assertIs<BoardApply.Committed>(
            send(el, "type" to "move", "jobId" to job, "idempotencyKey" to "$job#claim#2", "expectedRevision" to 2, "toColumn" to "running", "owner" to "claim:brain"),
        )
        assertEquals("claim:brain", el.card(job)!!.owner)
        assertEquals(BoardCol.RUNNING, el.card(job)!!.col)
        return run.revision
    }

    @Test
    fun claimedCardCannotGoRunningToDone() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("run-done")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "a")

        val refused = send(el, "type" to "move", "jobId" to "a", "idempotencyKey" to "x1", "expectedRevision" to rev, "toColumn" to "done")
        val why = assertIs<BoardApply.Rejected>(refused).reason
        assertTrue("claimed work passes review first" in why, why)
        assertEquals(BoardCol.RUNNING, el.card("a")!!.col, "the refusal changed nothing")
        assertEquals(rev, el.card("a")!!.revision)

        // the lifecycle verb is the same door
        val completed = send(el, "type" to "complete", "jobId" to "a", "idempotencyKey" to "x2", "expectedRevision" to rev)
        assertTrue("claimed work passes review first" in assertIs<BoardApply.Rejected>(completed).reason)

        // an UNclaimed running card still closes as it always did
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "h", "idempotencyKey" to "s-h", "owner" to "jim"))
        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to "h", "idempotencyKey" to "m-h", "expectedRevision" to 1, "toColumn" to "running"))
        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to "h", "idempotencyKey" to "d-h", "expectedRevision" to 2, "toColumn" to "done"))
        el.drain()
    }

    @Test
    fun reviewToDoneNeedsSomebodyOtherThanTheClaimant() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("review-done")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "b")
        val review = assertIs<BoardApply.Committed>(
            send(el, "type" to "move", "jobId" to "b", "idempotencyKey" to "b#claim-review#$rev", "expectedRevision" to rev, "toColumn" to "review"),
        )
        assertEquals(BoardCol.REVIEW, el.card("b")!!.col)
        assertEquals("claim:brain", el.card("b")!!.owner, "review keeps the claimant on the card")

        // the claimant closing its own ticket
        val self = send(el, "type" to "move", "jobId" to "b", "idempotencyKey" to "y1", "expectedRevision" to review.revision, "toColumn" to "done", "actor" to "claim:brain")
        val why = assertIs<BoardApply.Rejected>(self).reason
        assertTrue("second pair of eyes" in why, why)
        assertEquals(BoardCol.REVIEW, el.card("b")!!.col)

        // a human — a different actor — may
        val human = send(el, "type" to "move", "jobId" to "b", "idempotencyKey" to "y2", "expectedRevision" to review.revision, "toColumn" to "done", "actor" to "jim")
        assertIs<BoardApply.Committed>(human)
        assertEquals(BoardCol.DONE, el.card("b")!!.col)
        el.drain()
    }

    @Test
    fun guardSurvivesReplay() = runBlocking {
        val dir = tempDir("replay")
        val cas = CasStore.inMemory()
        val el1 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 5L })
        el1.open()
        val rev = claimed(el1, "c")
        el1.drain()

        val el2 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 5L })
        el2.open()
        assertEquals("claim:brain", el2.card("c")!!.owner, "the owner is WAL truth")
        val refused = send(el2, "type" to "move", "jobId" to "c", "idempotencyKey" to "z1", "expectedRevision" to rev, "toColumn" to "done")
        assertTrue("claimed work passes review first" in assertIs<BoardApply.Rejected>(refused).reason)
        el2.drain()
    }
}
