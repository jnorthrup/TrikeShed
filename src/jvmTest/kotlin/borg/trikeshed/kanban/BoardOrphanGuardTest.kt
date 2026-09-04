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
 * The orphan guard: the tree of work lives on the board. A Submit carrying
 * `parent` branches off a card that must exist and still be live; a Submit
 * without `parent` is intake. And the reaper's hand-back: `"owner": ""`
 * present-and-blank CLEARS the owner, an absent key keeps it.
 */
class BoardOrphanGuardTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "orphan-guard-$name-${System.nanoTime()}").apply { mkdirs() }

    private suspend fun send(el: BoardStoreElement, vararg pairs: Pair<String, Any?>): BoardApply {
        val d = CompletableDeferred<BoardApply>()
        el.intake.send(BoardIntake(mapOf(*pairs), d))
        return d.await()
    }

    @Test
    fun aSplitMustBranchOffALiveCard() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("split")), CasStore.inMemory(), clock = { 5L })
        el.open()
        // intake: no parent, allowed
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "root", "idempotencyKey" to "s-root", "title" to "Root"))

        // a bogus parent: refused, nothing written
        val bogus = send(el, "type" to "submit", "jobId" to "kid0", "idempotencyKey" to "s-kid0", "title" to "Kid", "parent" to "nope")
        assertEquals("orphan: parent nope is not live (no such card)", assertIs<BoardApply.Rejected>(bogus).reason)
        assertEquals(null, el.card("kid0"))

        // a live parent: allowed
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "kid1", "idempotencyKey" to "s-kid1", "title" to "Kid 1", "parent" to "root"))

        // the parent closes: further splits are orphans
        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to "root", "idempotencyKey" to "m-root", "expectedRevision" to 1, "toColumn" to "done"))
        val dead = send(el, "type" to "submit", "jobId" to "kid2", "idempotencyKey" to "s-kid2", "title" to "Kid 2", "parent" to "root")
        assertEquals("orphan: parent root is not live (it is 'done')", assertIs<BoardApply.Rejected>(dead).reason)

        // a blank parent is no parent
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "kid3", "idempotencyKey" to "s-kid3", "title" to "Kid 3", "parent" to "  "))
        el.drain()
    }

    @Test
    fun theParentEdgeReplaysWithTheCard() = runBlocking {
        val dir = tempDir("replay")
        val cas = CasStore.inMemory()
        val el = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 5L })
        el.open()
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "p", "idempotencyKey" to "s-p", "title" to "P"))
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "c", "idempotencyKey" to "s-c", "title" to "C", "parent" to "p"))
        assertIs<BoardApply.Rejected>(send(el, "type" to "submit", "jobId" to "o", "idempotencyKey" to "s-o", "title" to "O", "parent" to "ghost"))
        el.drain()

        val again = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 6L })
        again.open()
        assertEquals(setOf("p", "c"), again.cards().map { it.jobId }.toSet(), "the refused orphan never reached the WAL")
        again.drain()
    }

    @Test
    fun aBlankOwnerClearsAnAbsentOwnerKeeps() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("owner")), CasStore.inMemory(), clock = { 5L })
        el.open()
        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "w", "idempotencyKey" to "s-w", "title" to "W", "owner" to "claim:brain"))
        assertEquals("claim:brain", el.card("w")!!.owner)
        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to "w", "idempotencyKey" to "m1", "expectedRevision" to 1, "toColumn" to "ready"))
        assertEquals("claim:brain", el.card("w")!!.owner, "no owner key: the owner stays")
        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to "w", "idempotencyKey" to "m2", "expectedRevision" to 2, "toColumn" to "blocked", "owner" to ""))
        assertEquals("", el.card("w")!!.owner, "owner present and blank: cleared — a human sees the card")
        assertTrue(el.card("w")!!.col == BoardCol.BLOCKED)
        el.drain()
    }
}
