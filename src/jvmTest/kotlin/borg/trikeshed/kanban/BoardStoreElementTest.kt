package borg.trikeshed.kanban

import borg.trikeshed.job.CasStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

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

    /**
     * Delta 2026-09-05 (fan-out): the parent edge is stored, replays, and is guarded — a card
     * is never its own parent, a parent whose chain leads back to the card is a cycle, and a
     * dependency-only re-submit (the fan-out join) keeps the edge.
     */
    @Test
    fun parentIsStoredGuardedAndReplayed() = runBlocking {
        val dir = tempDir("parent")
        val cas = CasStore.inMemory()
        val el1 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 9L })
        el1.open()
        assertIs<BoardApply.Committed>(submit(el1, "p", "kp"))
        assertIs<BoardApply.Committed>(send(el1, "type" to "submit", "jobId" to "p-m1", "idempotencyKey" to "kc", "title" to "child", "parent" to "p"))
        assertEquals("p", el1.card("p-m1")!!.parent)
        assertEquals("", el1.card("p")!!.parent)

        // a card cannot be its own parent, even on a re-submit of a live card
        val self = send(el1, "type" to "submit", "jobId" to "p", "idempotencyKey" to "kself", "parent" to "p")
        assertTrue("cannot be its own parent" in (self as BoardApply.Rejected).reason, self.reason)
        assertEquals("", el1.card("p")!!.parent)
        // nor close a cycle through the chain: p-m1's parent is p, so p's parent cannot be p-m1
        val cycle = send(el1, "type" to "submit", "jobId" to "p", "idempotencyKey" to "kcyc", "parent" to "p-m1")
        assertTrue("tree cycle" in (cycle as BoardApply.Rejected).reason, cycle.reason)
        assertEquals("", el1.card("p")!!.parent)
        // the join (dependencies only) keeps the child's edge
        assertIs<BoardApply.Committed>(send(el1, "type" to "submit", "jobId" to "p-m1", "idempotencyKey" to "kjoin", "dependencies" to emptyList<String>(), "expectedRevision" to 1))
        assertEquals("p", el1.card("p-m1")!!.parent)
        val liveRows = el1.cards().sortedBy { it.jobId }
        el1.drain()

        val el2 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 9L })
        el2.open()
        assertEquals(liveRows, el2.cards().sortedBy { it.jobId }, "the parent edge replays with the card")
        assertEquals("p", el2.card("p-m1")!!.parent)
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

    @Test
    fun noReaderSeesAnUnflushedBatchAndFailureClosesIntake() = runBlocking {
        lateinit var el: BoardStoreElement
        val replies = (1..2).map { CompletableDeferred<BoardApply>() }
        val diskFailure = java.io.IOException("injected flush failure")
        val wal = object : BoardWalPort {
            var sequence = 0L
            override fun append(record: ByteArray): Long = ++sequence
            override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) = Unit
            override fun flush() {
                assertTrue(el.cards().isEmpty())
                assertNull(el.card("j1"))
                assertEquals(0, el.projection().cardCount)
                assertEquals(0L, el.lastSequence)
                assertTrue(replies.none { it.isCompleted })
                throw diskFailure
            }
        }
        el = BoardStoreElement(wal, CasStore.inMemory())
        replies.forEachIndexed { i, reply ->
            el.intake.send(BoardIntake(mapOf("type" to "submit", "jobId" to "j${i + 1}", "idempotencyKey" to "k$i"), reply))
        }
        el.open()
        replies.forEach { assertEquals(diskFailure.message, assertFailsWith<java.io.IOException> { it.await() }.message) }
        assertTrue(el.cards().isEmpty())
        assertEquals(0, el.projection().cardCount)
        assertEquals(0L, el.lastSequence)
        assertEquals(diskFailure.message, el.failure?.message)
        assertTrue(el.intake.trySend(BoardIntake(emptyMap<String, Any?>())).isFailure)
        el.drain()
    }

    @Test
    fun beforeJobIdInsertsBetweenCardsAndSurvivesReplay() = runBlocking {
        val dir = tempDir("ordering")
        val cas = CasStore.inMemory()
        val el = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 1L })
        el.open()
        for (j in listOf("a", "b", "c")) submit(el, j, "k-$j")
        // all three sit in todo at submit order a,b,c — move c BEFORE a
        send(el, "type" to "move", "jobId" to "c", "idempotencyKey" to "m1",
            "expectedRevision" to 1, "toColumn" to "todo", "beforeJobId" to "a")
        fun ordered(e: BoardStoreElement) =
            e.cards().filter { it.col == BoardCol.TODO }.sortedBy { it.order }.map { it.jobId }
        assertEquals(listOf("c", "a", "b"), ordered(el), "same-lane reorder inserts BETWEEN cards")

        // cross-lane positional insert: b → ready (alone), then a → ready BEFORE b
        send(el, "type" to "move", "jobId" to "b", "idempotencyKey" to "m2",
            "expectedRevision" to 1, "toColumn" to "ready")
        send(el, "type" to "move", "jobId" to "a", "idempotencyKey" to "m3",
            "expectedRevision" to 1, "toColumn" to "ready", "beforeJobId" to "b")
        val ready = el.cards().filter { it.col == BoardCol.READY }.sortedBy { it.order }.map { it.jobId }
        assertEquals(listOf("a", "b"), ready, "cross-lane insert-before lands at the pointed position")

        // an unknown beforeJobId degrades to append, never an error
        val ok = send(el, "type" to "move", "jobId" to "c", "idempotencyKey" to "m4",
            "expectedRevision" to 2, "toColumn" to "ready", "beforeJobId" to "card-gone")
        assertIs<BoardApply.Committed>(ok)
        assertEquals(listOf("a", "b", "c"),
            el.cards().filter { it.col == BoardCol.READY }.sortedBy { it.order }.map { it.jobId })
        el.drain()

        // replay: a fresh element over the same WAL derives the SAME packing
        val el2 = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 1L })
        el2.open()
        assertEquals(listOf("a", "b", "c"),
            el2.cards().filter { it.col == BoardCol.READY }.sortedBy { it.order }.map { it.jobId },
            "beforeJobId ordering is replay-stable — the raw map is the WAL truth")
        el2.drain()
    }
}
