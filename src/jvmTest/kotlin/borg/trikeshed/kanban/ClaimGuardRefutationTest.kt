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
 * The claim guard's refutation probes, carried in from the orphaned
 * `wf/kanban-claim-review` worktree (2026-09-04) and turned around.
 *
 * On that branch each probe ASSERTED the hole — a passing test meant a claimant
 * could settle its own card. Master closed all four on 2026-09-04 (the verifier
 * deltas in [BoardStoreElement]: ARCHIVED gated like DONE, `cancel`/`retract`
 * through the same door, and `ownerGuard` refusing the re-owning move), so each
 * probe is inverted here and stands as the regression test that the door stays
 * shut. Where the settling step could be refused at either move, the assertion
 * is on the card never reaching a settled column, not on which guard spoke.
 */
class ClaimGuardRefutationTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "claim-refute-$name-${System.nanoTime()}").apply { mkdirs() }

    private suspend fun send(el: BoardStoreElement, vararg pairs: Pair<String, Any?>): BoardApply {
        val d = CompletableDeferred<BoardApply>()
        el.intake.send(BoardIntake(mapOf(*pairs), d))
        return d.await()
    }

    private suspend fun claimed(el: BoardStoreElement, job: String): Long {
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to job, "idempotencyKey" to "s-$job", "title" to "Card $job"))
        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to job, "idempotencyKey" to "r-$job", "expectedRevision" to 1, "toColumn" to "ready"))
        val run = assertIs<BoardApply.Committed>(
            send(el, "type" to "move", "jobId" to job, "idempotencyKey" to "$job#claim#2", "expectedRevision" to 2, "toColumn" to "running", "owner" to "claim:brain"),
        )
        assertEquals("claim:brain", el.card(job)!!.owner)
        return run.revision
    }

    /** Path 1 (closed): ARCHIVED settles a card, so the gate looks at it exactly as it looks at DONE. */
    @Test
    fun claimantCannotArchiveItsOwnRunningCard() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("archive")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "a")
        val r = send(el, "type" to "move", "jobId" to "a", "idempotencyKey" to "x", "expectedRevision" to rev, "toColumn" to "archived", "actor" to "claim:brain")
        val why = assertIs<BoardApply.Rejected>(r, "RUNNING → ARCHIVED by the claimant must not skip review").reason
        assertTrue("claimed work passes review first" in why, why)
        assertEquals(BoardCol.RUNNING, el.card("a")!!.col)
        el.drain()
    }

    /** Path 2 (closed): the lifecycle verbs land in ARCHIVED, so they are the same door. */
    @Test
    fun claimantCannotCancelItsOwnRunningCard() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("cancel")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "b")
        val r = send(el, "type" to "cancel", "jobId" to "b", "idempotencyKey" to "x", "expectedRevision" to rev, "actor" to "claim:brain")
        assertIs<BoardApply.Rejected>(r, "cancel by the claimant must not settle the card without review")
        assertEquals(BoardCol.RUNNING, el.card("b")!!.col)
        el.drain()
    }

    @Test
    fun claimantCannotRetractItsOwnRunningCard() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("retract")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "c")
        val r = send(el, "type" to "retract", "jobId" to "c", "idempotencyKey" to "x", "expectedRevision" to rev, "actor" to "claim:brain")
        assertIs<BoardApply.Rejected>(r, "retract by the claimant must not erase the card without review")
        assertEquals(BoardCol.RUNNING, el.card("c")!!.col)
        el.drain()
    }

    /**
     * Path 3 (closed): clear the owner, then close. `ownerGuard` refuses the re-owning
     * move; the assertion is that the card never reaches DONE, whichever step speaks.
     */
    @Test
    fun claimantCannotClearItsOwnerThenCloseTheCard() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("owner-clear")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "d")
        val clear = send(el, "type" to "move", "jobId" to "d", "idempotencyKey" to "x1", "expectedRevision" to rev, "toColumn" to "running", "owner" to "", "actor" to "claim:brain")
        if (clear is BoardApply.Committed) {
            send(el, "type" to "move", "jobId" to "d", "idempotencyKey" to "x2", "expectedRevision" to clear.revision, "toColumn" to "done", "actor" to "claim:brain")
        }
        assertEquals(BoardCol.RUNNING, el.card("d")!!.col, "the two-step owner-clear must not settle a claimed card")
        assertEquals("claim:brain", el.card("d")!!.owner, "the claim is released by the reaper or the judge, not by re-owning")
        el.drain()
    }

    /** Still open by design, and stated so it cannot drift silently: an unlabeled gesture from REVIEW passes. */
    @Test
    fun reviewToDoneWithNoActorPasses() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("no-actor")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "e")
        val rv = assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to "e", "idempotencyKey" to "e#claim-review#$rev", "expectedRevision" to rev, "toColumn" to "review"))
        // exactly the map BoardClaimWorker / MCP kanban.move / /api/lcnc/kanban/move produce: no actor key at all
        val done = send(el, "type" to "move", "jobId" to "e", "idempotencyKey" to "x", "expectedRevision" to rv.revision, "toColumn" to "done")
        assertIs<BoardApply.Committed>(done, "an unlabeled human gesture from REVIEW is the documented pass")
        el.drain()
    }

    /** The single-step blank owner on the DONE move itself: the guard reads the previous row, so it is refused. */
    @Test
    fun blankOwnerOnTheDoneMoveIsStillRefused() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("blank-on-done")), CasStore.inMemory(), clock = { 5L })
        el.open()
        val rev = claimed(el, "g")
        val r = send(el, "type" to "move", "jobId" to "g", "idempotencyKey" to "x", "expectedRevision" to rev, "toColumn" to "done", "owner" to "", "actor" to "claim:brain")
        assertIs<BoardApply.Rejected>(r, "the guard reads the previous row, so a blank owner on the closing move changes nothing")
        el.drain()
    }

    /** What the WAL persists is the raw map with wire strings and a stamped clock, never a column ordinal. */
    @Test
    fun walCarriesWireStringsNotOrdinals() = runBlocking {
        val dir = tempDir("wal-wire")
        val cas = CasStore.inMemory()
        val el1 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 5L })
        el1.open()
        assertIs<BoardApply.Committed>(send(el1, "type" to "submit", "jobId" to "w", "idempotencyKey" to "s", "title" to "W"))
        assertIs<BoardApply.Committed>(send(el1, "type" to "move", "jobId" to "w", "idempotencyKey" to "m1", "expectedRevision" to 1, "toColumn" to "review"))
        assertIs<BoardApply.Committed>(send(el1, "type" to "move", "jobId" to "w", "idempotencyKey" to "m2", "expectedRevision" to 2, "toColumn" to "done"))
        el1.drain()
        val records = ArrayList<String>()
        JvmBoardWal(dir).replay { _, bytes -> records.add(bytes.decodeToString()) }
        assertTrue(records.all { it.startsWith("w\t") }, "WAL records are jobId TAB cid: $records")
        val payloads = records.map { cas.get(borg.trikeshed.job.ContentId(it.substringAfter('\t').trim()))!!.decodeToString() }
        assertTrue(payloads.any { "\"toColumn\":\"done\"" in it }, "column persisted as wire string: $payloads")
        assertTrue(payloads.all { "\"atMs\"" in it }, "atMs stamped into every durable record: $payloads")
        val el2 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 99L })
        el2.open()
        assertEquals(BoardCol.DONE, el2.card("w")!!.col)
        assertEquals(5L, el2.card("w")!!.lastMoveMs, "replay re-derives lastMoveMs from the stamped atMs, not boot clock")
        el2.drain()
    }
}
