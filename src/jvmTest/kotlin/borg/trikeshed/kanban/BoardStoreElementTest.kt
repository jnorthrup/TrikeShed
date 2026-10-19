package borg.trikeshed.kanban

import borg.trikeshed.job.CasStore
import borg.trikeshed.couch.isam.WalFrame
import borg.trikeshed.userspace.nio.channels.FileChannel as UserspaceFileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption as UserspaceOpenOption
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

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

    @Test
    fun malformedRevisionCannotBecomeAnUnguardedCommand() {
        val invalid = arrayOf<Any>("", "invalid", "1.0", 1.5, 1.5f, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Long.MAX_VALUE.toDouble(),
            Long.MIN_VALUE.toDouble() * 2, false)
        for (type in arrayOf("submit", "move")) for (field in arrayOf("expectedRevision", "revision")) {
            for (value in invalid) {
                val raw = mapOf("type" to type, "jobId" to "card", "idempotencyKey" to "revision", field to value) +
                    if (type == "move") mapOf("toColumn" to "ready") else emptyMap()
                val rejected = assertIs<InvokeLowering.Outcome.Rejected>(InvokeLowering.lower(raw), "$type $field=$value")
                assertEquals("invalid expectedRevision", rejected.reason)
            }
        }
    }

    @Test
    fun integralRevisionPreservesItsExactValueAndOmission() {
        val raw = mapOf("type" to "submit", "jobId" to "card", "idempotencyKey" to "revision")
        val values = arrayOf<Pair<Any, Long>>(1 to 1L, 1L to 1L, 1.0 to 1L, 1.0f to 1L,
            "1" to 1L, Long.MAX_VALUE to Long.MAX_VALUE, Long.MAX_VALUE.toString() to Long.MAX_VALUE)
        for ((value, expected) in values) {
            val lowered = assertIs<InvokeLowering.Outcome.Lowered>(InvokeLowering.lower(raw + ("expectedRevision" to value)))
            assertEquals(expected, assertIs<borg.trikeshed.job.JobCommand.Submit>(lowered.command).expectedRevision)
        }
        val omitted = assertIs<InvokeLowering.Outcome.Lowered>(InvokeLowering.lower(raw))
        assertNull(assertIs<borg.trikeshed.job.JobCommand.Submit>(omitted.command).expectedRevision)
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
    fun lcncSubmitRefusesStaleWiredRevision() = runBlocking {
        for (port in listOf("command", "command?")) {
            val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
            try {
                store.open()
                assertIs<BoardApply.Committed>(submit(store, "card", "create"))
                val runner = borg.trikeshed.lcnc.LcncKanbanExperience(store).registry().getValue("kanban.submit")
                suspend fun edit(key: String, title: String, revision: Double, fallback: String): Map<String, Any?> =
                    runner.run(borg.trikeshed.lcnc.LcncNode("edit", "kanban.submit",
                        mapOf("expectedRevision" to fallback)), mapOf(port to mapOf(
                        "jobId" to "card", "title" to title, "expectedRevision" to revision,
                        "idempotencyKey" to key,
                    )))

                val accepted = edit("fresh", "Current title", 1.0, "99")
                assertEquals(true, accepted["accepted"], "wired revision must override the parameter")
                assertEquals(2L, accepted["revision"])
                val rejected = edit("stale", "Stale overwrite", 1.0, "2")
                assertEquals(false, rejected["accepted"], "$port must preserve the store's revision precondition")
                assertTrue(rejected["reason"].toString().contains("stale expectedRevision"))
                assertEquals("Current title", store.card("card")?.title)
                assertEquals(2L, store.card("card")?.revision)
                val sheets = assertIs<Map<*, *>>(rejected["sheets"])
                val board = assertIs<Map<*, *>>(sheets["boardView"])
                val card = assertIs<Map<*, *>>(assertIs<List<*>>(board["items"]).single())
                assertEquals("Current title", card["title"], "refusal projects current state, not the rejected edit")
                assertEquals(2L, card["revision"])
            } finally { store.close() }
        }
    }

    @Test
    fun lcncSubmitRefusesStaleParameterRevision() = runBlocking {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            assertIs<BoardApply.Committed>(submit(store, "card", "create"))
            val runner = borg.trikeshed.lcnc.LcncKanbanExperience(store).registry().getValue("kanban.submit")
            suspend fun edit(key: String, title: String) = runner.run(
                borg.trikeshed.lcnc.LcncNode("edit", "kanban.submit", mapOf(
                    "jobId" to "card", "title" to title, "expectedRevision" to "1", "idempotencyKey" to key,
                )), emptyMap())
            assertEquals(true, edit("fresh", "Current title")["accepted"])
            val rejected = edit("stale", "Stale overwrite")
            assertEquals(false, rejected["accepted"], "parameter edits have the same store precondition")
            assertTrue(rejected["reason"].toString().contains("stale expectedRevision"))
            assertEquals("Current title", store.card("card")?.title)
            assertEquals(2L, store.card("card")?.revision)
        } finally { store.close() }
    }

    @Test
    fun lcncSubmitWithoutRevisionKeepsStoreSemantics() = runBlocking {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            val runner = borg.trikeshed.lcnc.LcncKanbanExperience(store).registry().getValue("kanban.submit")
            suspend fun edit(key: String, fields: Map<String, Any?>) = runner.run(
                borg.trikeshed.lcnc.LcncNode("edit", "kanban.submit"),
                mapOf("command" to (fields + mapOf("jobId" to "card", "idempotencyKey" to key))))
            assertEquals(true, edit("create", mapOf("title" to "Current title", "priority" to 0))["accepted"])
            assertEquals(true, edit("metadata", mapOf("tags" to listOf("reviewed")))["accepted"])
            assertEquals("Current title", store.card("card")?.title)
            assertEquals(0, store.card("card")?.priority)
            assertEquals(2L, store.card("card")?.revision)
            assertEquals(listOf("reviewed"), store.card("card")?.tags)
        } finally { store.close() }
    }

    @Test
    fun lcncSubmitRejectsMalformedExplicitRevisionWithoutChangingTheCard() = runBlocking {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            assertIs<BoardApply.Committed>(submit(store, "card", "create"))
            val runner = borg.trikeshed.lcnc.LcncKanbanExperience(store).registry().getValue("kanban.submit")
            for (wired in arrayOf(false, true)) for (revision in arrayOf("", "invalid", "1.5")) {
                val fields = mapOf("jobId" to "card", "title" to "Invalid overwrite",
                    "idempotencyKey" to "invalid-$wired-$revision", "expectedRevision" to revision)
                val result = if (wired) runner.run(borg.trikeshed.lcnc.LcncNode("edit", "kanban.submit",
                    mapOf("expectedRevision" to "1")), mapOf("command" to fields))
                else runner.run(borg.trikeshed.lcnc.LcncNode("edit", "kanban.submit", fields), emptyMap())
                assertEquals(false, result["accepted"], "wired=$wired, revision='$revision'")
                assertEquals("invalid expectedRevision", result["reason"])
                assertEquals("Card card", store.card("card")?.title)
                assertEquals(1L, store.card("card")?.revision)
            }
        } finally { store.close() }
    }

    @Test
    fun lcncSubmitContractMakesRevisionExplicitlyOptional() {
        val contract = requireNotNull(borg.trikeshed.lcnc.LcncContracts.find("kanban.submit"))
        val revision = requireNotNull(contract.params["expectedRevision"])
        assertTrue(revision.optional, "opening the parameter editor must not add a precondition")
        assertEquals("", revision.v, "the editor must not invent a card revision")
    }

    @Test
    fun legacyWalFramesReopenWithIdenticalBytesAndMonotonicSequences() = runBlocking {
        val dir = tempDir("legacy-frames")
        val file = File(dir, "board.wal")
        val legacy = WalFrame.encode(4, "first".encodeToByteArray()) + WalFrame.encode(9, "second".encodeToByteArray())
        file.writeBytes(legacy)
        val wal = JvmBoardWal(dir)
        try {
            assertEquals(10L, wal.append("third".encodeToByteArray()), "Append initializes its sequence even before replay")
            wal.flush()
            assertContentEquals(legacy + WalFrame.encode(10, "third".encodeToByteArray()), file.readBytes())
            val records = mutableListOf<Pair<Long, String>>()
            wal.replay { sequence, payload -> records += sequence to payload.decodeToString() }
            assertEquals(listOf(4L to "first", 9L to "second", 10L to "third"), records)
        } finally { wal.close() }
        wal.close()
        assertFailsWith<IllegalStateException> { wal.append(byteArrayOf()) }
        val reopened = JvmBoardWal(dir)
        try {
            assertEquals(11L, reopened.append("fourth".encodeToByteArray()))
            reopened.flush()
            val sequences = mutableListOf<Long>()
            reopened.replay { sequence, _ -> sequences += sequence }
            assertEquals(listOf(4L, 9L, 10L, 11L), sequences)
        } finally { reopened.close() }
    }

    @Test
    fun onlyIncompleteFinalWalHeadersOrPayloadsAreTruncated() = runBlocking {
        val prefix = WalFrame.encode(1, "first".encodeToByteArray()) + WalFrame.encode(2, "second".encodeToByteArray())
        val next = WalFrame.encode(3, "third".encodeToByteArray())
        for (cut in listOf(1, WalFrame.HEADER_SIZE - 1, WalFrame.HEADER_SIZE, WalFrame.HEADER_SIZE + 2, next.size - 1)) {
            val dir = tempDir("short-frame-$cut")
            val file = File(dir, "board.wal")
            file.writeBytes(prefix + next.copyOf(cut))
            val wal = JvmBoardWal(dir)
            try {
                assertContentEquals(prefix, file.readBytes())
                val sequences = mutableListOf<Long>()
                wal.replay { sequence, _ -> sequences += sequence }
                assertEquals(listOf(1L, 2L), sequences)
                assertEquals(3L, wal.append("third".encodeToByteArray()))
                wal.flush()
                assertContentEquals(prefix + next, file.readBytes())
            } finally { wal.close() }
        }
    }

    @Test
    fun completeWalCorruptionRefusesWithoutTruncatingCommittedBytes() {
        val prefix = WalFrame.encode(1, "first".encodeToByteArray())
        val frame = WalFrame.encode(2, "second".encodeToByteArray())
        val corruptions = listOf(
            "magic" to frame.copyOf().also { it[0] = 0 },
            "version" to frame.copyOf().also { it[5] = 2 },
            "checksum" to frame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            "sequence" to WalFrame.encode(1, "duplicate".encodeToByteArray()),
            "length" to frame.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(14, 10 * 1024 * 1024 + 1) },
        )
        for ((kind, invalid) in corruptions) {
            val dir = tempDir("bad-frame-$kind")
            val file = File(dir, "board.wal")
            val bytes = prefix + invalid
            file.writeBytes(bytes)
            assertFailsWith<IllegalStateException>(kind) { JvmBoardWal(dir) }
            assertContentEquals(bytes, file.readBytes(), "A complete corrupt $kind frame must remain available for diagnosis")
        }
    }

    @Test
    fun walDirectorySyncFailurePropagatesAndHandlesCloseAfterFailure() {
        val dir = tempDir("sync-failure")
        val channel = UserspaceFileChannel.open(File(dir, "board.wal").path,
            UserspaceOpenOption.READ, UserspaceOpenOption.WRITE, UserspaceOpenOption.CREATE)
        var fail = false
        var directorySyncs = 0
        val wal = BoardFileWal(channel, StorageDurability.DURABLE) {
            if (fail) throw java.io.IOException("injected directory sync failure")
            UserspaceFileChannel.open(dir.path, UserspaceOpenOption.READ).use { it.force(true) }
            directorySyncs++
        }
        try {
            assertEquals(1, directorySyncs)
            wal.append("one".encodeToByteArray())
            wal.append("two".encodeToByteArray())
            wal.flush()
            assertEquals(2, directorySyncs, "One group flush syncs the directory once")
            fail = true
            assertEquals("injected directory sync failure", assertFailsWith<java.io.IOException> { wal.flush() }.message)
            assertFailsWith<IllegalStateException> { wal.append("uncertain".encodeToByteArray()) }
        } finally { wal.close() }
        wal.close()
        assertFalse(channel.isOpen())

        val opening = UserspaceFileChannel.open(File(dir, "opening.wal").path,
            UserspaceOpenOption.READ, UserspaceOpenOption.WRITE, UserspaceOpenOption.CREATE)
        assertFailsWith<java.io.IOException> {
            BoardFileWal(opening, StorageDurability.DURABLE) { throw java.io.IOException("opening sync failed") }
        }
        assertFalse(opening.isOpen(), "Failed initialization releases its userspace descriptor")
        val nested = File(dir, "new/child")
        assertEquals(StorageDurability.DURABLE, BoardFileWal(JvmFileOperations(), nested.path).let {
            try { it.append("nested".encodeToByteArray()); it.flush(); it.durability } finally { it.close() }
        })
        assertContentEquals(WalFrame.encode(1, "nested".encodeToByteArray()), File(nested, "board.wal").readBytes())
    }

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
    fun groupCommitFlushesOncePerBatch(): Unit = runBlocking {
        val dir = tempDir("group")
        var flushes = 0
        val counting = object : BoardWalPort {
            val inner = JvmBoardWal(dir)
            override fun append(record: ByteArray): Long = inner.append(record)
            override fun flush() { flushes++; inner.flush() }
            override fun close() = inner.close()
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
        assertFailsWith<IllegalStateException> { counting.inner.append(byteArrayOf()) }
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
