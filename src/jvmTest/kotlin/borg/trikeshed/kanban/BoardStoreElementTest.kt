package borg.trikeshed.kanban

import borg.trikeshed.job.CasStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InvokeLoweringTest {

    @Test
    fun loweringRoundTripsAndRefusesLoudly() {
        // today's PWA batch shape
        val batch = InvokeLowering.lowerBatch(
            mapOf(
                "userId" to "jim",
                "commands" to listOf(
                    mapOf("type" to "submit", "jobId" to "a", "idempotencyKey" to "k1"),
                    mapOf("type" to "move", "jobId" to "a", "idempotencyKey" to "k2", "expectedRevision" to 1.0, "toColumn" to "ready"),
                    // the PWA's mislabeled card-click: Submit smuggling a move — refused with a REASON
                    mapOf("type" to "submit", "jobId" to "b", "idempotencyKey" to "k3", "toColumn" to "done"),
                    mapOf("type" to "warp", "jobId" to "c", "idempotencyKey" to "k4"),
                    mapOf("type" to "move", "jobId" to "d", "idempotencyKey" to "k5", "expectedRevision" to 1, "toColumn" to "col-agentic"),
                ),
            ),
        )
        assertEquals(5, batch.size)
        assertIs<InvokeLowering.Outcome.Lowered>(batch[0])
        val move = assertIs<InvokeLowering.Outcome.Lowered>(batch[1]).command
        assertIs<borg.trikeshed.job.JobCommand.Move>(move)
        assertEquals("ready", (move as borg.trikeshed.job.JobCommand.Move).toColumn.value)
        val mislabeled = assertIs<InvokeLowering.Outcome.Rejected>(batch[2])
        assertTrue("mislabeled" in mislabeled.reason, mislabeled.reason)
        val unknown = assertIs<InvokeLowering.Outcome.Rejected>(batch[3])
        assertTrue("unknown command type" in unknown.reason)
        // legacy col-* folds into the canonical seven
        val legacy = assertIs<InvokeLowering.Outcome.Lowered>(batch[4]).command as borg.trikeshed.job.JobCommand.Move
        assertEquals("running", legacy.toColumn.value)
    }

    @Test
    fun missingFieldsNameThemselves() {
        val noKey = InvokeLowering.lower(mapOf("type" to "submit", "jobId" to "x"))
        assertTrue("idempotencyKey" in (noKey as InvokeLowering.Outcome.Rejected).reason)
        val noRev = InvokeLowering.lower(mapOf("type" to "move", "jobId" to "x", "idempotencyKey" to "k", "toColumn" to "done"))
        assertTrue("expectedRevision" in (noRev as InvokeLowering.Outcome.Rejected).reason)
        val badCol = InvokeLowering.lower(mapOf("type" to "move", "jobId" to "x", "idempotencyKey" to "k", "expectedRevision" to 1, "toColumn" to "nowhere"))
        assertTrue("unknown column" in (badCol as InvokeLowering.Outcome.Rejected).reason)
    }
}

class BoardStoreElementTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "board-test-$name-${System.nanoTime()}").apply { mkdirs() }

    private suspend fun send(el: BoardStoreElement, vararg pairs: Pair<String, Any?>): BoardApply {
        val d = CompletableDeferred<BoardApply>()
        el.intake.send(BoardIntake(mapOf(*pairs), d))
        return d.await()
    }

    private suspend fun submit(el: BoardStoreElement, job: String, key: String, deps: List<String> = emptyList()): BoardApply =
        send(el, "type" to "submit", "jobId" to job, "idempotencyKey" to key, "title" to "Card $job", "dependencies" to deps)

    @Test
    fun commitMoveRevisionAndGuards() = runBlocking {
        val dir = tempDir("guards")
        val el = BoardStoreElement(JvmBoardWal(dir), CasStore.inMemory(), clock = { 42L })
        el.open()

        val c1 = assertIs<BoardApply.Committed>(submit(el, "a", "k1"))
        assertEquals(1L, c1.revision)
        assertEquals(BoardCol.TODO, el.card("a")!!.col)
        assertEquals("Card a", el.card("a")!!.title)

        // idempotency dedupe
        val dup = submit(el, "a", "k1")
        assertTrue("duplicate idempotencyKey" in (dup as BoardApply.Rejected).reason)

        // committed Move refines the column + bumps revision
        val mv = assertIs<BoardApply.Committed>(
            send(el, "type" to "move", "jobId" to "a", "idempotencyKey" to "k2", "expectedRevision" to 1, "toColumn" to "ready"),
        )
        assertEquals(2L, mv.revision)
        assertEquals(BoardCol.READY, el.card("a")!!.col)

        // stale expectedRevision refused by the reducer
        val stale = send(el, "type" to "move", "jobId" to "a", "idempotencyKey" to "k3", "expectedRevision" to 1, "toColumn" to "done")
        assertTrue("stale expectedRevision" in (stale as BoardApply.Rejected).reason)

        // WIP limit: RUNNING holds 3
        for (j in listOf("w1", "w2", "w3")) {
            assertIs<BoardApply.Committed>(submit(el, j, "s-$j"))
            assertIs<BoardApply.Committed>(send(el, "type" to "start", "jobId" to j, "idempotencyKey" to "st-$j", "expectedRevision" to 1))
        }
        assertIs<BoardApply.Committed>(submit(el, "w4", "s-w4"))
        val full = send(el, "type" to "start", "jobId" to "w4", "idempotencyKey" to "st-w4", "expectedRevision" to 1)
        assertTrue("WIP limit" in (full as BoardApply.Rejected).reason)

        // dependency cycle refused at the door
        assertIs<BoardApply.Committed>(submit(el, "x", "kx", deps = listOf("y")))
        val cyc = submit(el, "y", "ky", deps = listOf("x"))
        assertTrue("cycle" in (cyc as BoardApply.Rejected).reason)
        el.drain()
    }

    @Test
    fun restartRebuildsIdenticalBoard() = runBlocking {
        val dir = tempDir("restart")
        val cas = CasStore.inMemory()
        val el1 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 7L })
        el1.open()
        submit(el1, "a", "k1")
        send(el1, "type" to "move", "jobId" to "a", "idempotencyKey" to "k2", "expectedRevision" to 1, "toColumn" to "ready")
        submit(el1, "b", "k3", deps = listOf("a"))
        send(el1, "type" to "start", "jobId" to "a", "idempotencyKey" to "k4", "expectedRevision" to 2)
        val liveRows = el1.cards().sortedBy { it.jobId }.map { it.copy(lastMoveMs = 0) }
        val liveSeq = el1.lastSequence
        el1.drain()

        // WAL is the state: a fresh element over the same forge home is the same board.
        val el2 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 7L })
        el2.open()
        assertEquals(liveSeq, el2.lastSequence)
        assertEquals(liveRows, el2.cards().sortedBy { it.jobId }.map { it.copy(lastMoveMs = 0) })
        // and the projection agrees (C05: rebuild == live)
        assertEquals("active", el2.projection().card("a")!!.lifecycle)
        el2.drain()
    }

    @Test
    fun tornTailTruncatesToCommittedPrefix() = runBlocking {
        val dir = tempDir("torn")
        val cas = CasStore.inMemory()
        val el1 = BoardStoreElement(JvmBoardWal(dir), cas)
        el1.open()
        submit(el1, "a", "k1")
        submit(el1, "b", "k2")
        el1.drain()

        File(dir, "board.wal").appendBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x01))

        val el2 = BoardStoreElement(JvmBoardWal(dir), cas)
        el2.open()
        assertEquals(setOf("a", "b"), el2.cards().map { it.jobId }.toSet())
        assertEquals(2L, el2.lastSequence)
        el2.drain()
    }

    @Test
    fun groupCommitFlushesOncePerBatch() = runBlocking {
        val dir = tempDir("group")
        var flushes = 0
        val counting = object : BoardWalPort {
            val inner = JvmBoardWal(dir)
            override fun append(record: ByteArray): Long = inner.append(record)
            override fun flush() { flushes++; inner.flush() }
            override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) = inner.replay(onRecord)
        }
        val el = BoardStoreElement(counting, CasStore.inMemory())
        // queue five commands BEFORE the consumer starts: one drained batch, ONE flush
        val replies = (1..5).map { i ->
            CompletableDeferred<BoardApply>().also {
                el.intake.send(BoardIntake(mapOf("type" to "submit", "jobId" to "j$i", "idempotencyKey" to "k$i"), it))
            }
        }
        el.open()
        replies.forEach { assertIs<BoardApply.Committed>(it.await()) }
        assertEquals(1, flushes, "group commit must flush once for the drained batch")
        el.drain()
    }
}
