package borg.trikeshed.kanban

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.context.ElementState
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BoardStoreDurabilityTest {
    private class ReplayWal(private val records: List<Pair<Long, ByteArray>>) : BoardWalPort {
        var closed = false
        var replayed = 0
        var flushes = 0
        private var sequence = records.lastOrNull()?.first ?: 0L

        override fun append(record: ByteArray): Long {
            check(!closed)
            return ++sequence
        }

        override fun flush() { check(!closed); flushes++ }
        override fun close() { closed = true }
        override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) {
            for ((sequence, record) in records) { replayed++; onRecord(sequence, record) }
        }
    }

    private fun pointer(jobId: String, cid: ContentId): ByteArray = "$jobId\t${cid.value}".encodeToByteArray()

    private suspend fun refusesReplay(cas: CasStore, record: ByteArray, reason: String, badSequence: Long = 2L) {
        val prefix = cas.put(jsonOf(mapOf("type" to "submit", "jobId" to "prefix", "idempotencyKey" to "prefix")).encodeToByteArray())
        val after = cas.put(jsonOf(mapOf("type" to "submit", "jobId" to "after", "idempotencyKey" to "after")).encodeToByteArray())
        val wal = ReplayWal(listOf(1L to pointer("prefix", prefix), badSequence to record, 3L to pointer("after", after)))
        val store = BoardStoreElement(wal, cas)
        val pending = queue(store, "never")
        try {
            val failure = assertFailsWith<Exception> { store.open() }
            assertTrue(failure.message.orEmpty().contains(reason), failure.message)
            assertEquals(failure.message, assertFailsWith<Exception> { pending.await() }.message)
            assertTrue(wal.closed, "An unsuccessful replay releases its WAL handle")
            assertEquals(2, wal.replayed, "A committed corrupt record cannot be skipped")
            assertEquals(ElementState.CLOSED, store.state)
            assertTrue(store.cards().isEmpty(), "A partial recovered board cannot become visible")
            assertEquals(0L, store.lastSequence)
            assertTrue(store.failure != null)
            assertTrue(store.intake.trySend(BoardIntake(emptyMap<String, Any?>())).isFailure)
            assertFailsWith<IllegalStateException> { store.open() }
            store.drain()
        } finally { store.close() }
    }

    @Test
    fun committedPointersRequirePresentDigestVerifiedPayloads() = runTest {
        val absent = ContentId.of("absent".encodeToByteArray())
        refusesReplay(CasStore.inMemory(), pointer("bad", absent), "Missing board WAL payload")
        val expected = ContentId.of("expected".encodeToByteArray())
        val cas = object : CasStore() {
            override fun get(cid: ContentId): ByteArray? =
                if (cid == expected) "different bytes".encodeToByteArray() else super.get(cid)
        }
        refusesReplay(cas, pointer("bad", expected), "payload digest mismatch")
    }

    @Test
    fun completeMalformedCommandsAndPointersCannotBeSkipped() = runTest {
        val cas = CasStore.inMemory()
        refusesReplay(cas, "missing separator".encodeToByteArray(), "Invalid board WAL pointer")
        refusesReplay(cas, "bad\tnot-a-cid".encodeToByteArray(), "ContentId must be")
        for (text in listOf("[]", "null")) {
            refusesReplay(cas, pointer("bad", cas.put(text.encodeToByteArray())), "not an object")
        }
        val valid = jsonOf(mapOf("type" to "submit", "jobId" to "bad", "idempotencyKey" to "bad"))
        refusesReplay(cas, pointer("bad", cas.put("$valid trailing".encodeToByteArray())), "Trailing JSON input")
        refusesReplay(cas, pointer("other", cas.put(valid.encodeToByteArray())), "job identity mismatch")
        refusesReplay(cas, pointer("bad", cas.put(valid.encodeToByteArray())), "Non-monotonic", badSequence = 1L)
    }

    @Test
    fun loweringGuardsAndReducerRejectionsInvalidateACommittedRecord() = runTest {
        val cas = CasStore.inMemory()
        for ((raw, reason) in listOf(
            mapOf("type" to "warp", "jobId" to "bad", "idempotencyKey" to "bad") to "unknown command type",
            mapOf("type" to "submit", "jobId" to "bad", "idempotencyKey" to "bad", "parent" to "absent") to "parent",
            mapOf("type" to "submit", "jobId" to "prefix", "idempotencyKey" to "prefix") to "duplicate idempotencyKey",
            mapOf("type" to "move", "jobId" to "prefix", "idempotencyKey" to "move", "expectedRevision" to 0,
                "toColumn" to "ready") to "stale expectedRevision",
        )) {
            refusesReplay(cas, pointer(raw["jobId"] as String, cas.put(jsonOf(raw).encodeToByteArray())), reason)
        }
    }

    @Test
    fun validLegacyCommandsRetainTheirIdentityAndAllowTheNextCommit() = runTest {
        val cas = CasStore.inMemory()
        val jobId = "legacy\tcard"
        val submit = cas.put(jsonOf(mapOf("kind" to "create", "id" to jobId, "idempotencyKey" to "old-submit",
            "title" to "Legacy title", "atMs" to 7)).encodeToByteArray())
        val move = cas.put(jsonOf(mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to "old-move",
            "expectedRevision" to 1.0, "toColumnId" to "col-agentic", "atMs" to 8)).encodeToByteArray())
        val wal = ReplayWal(listOf(4L to pointer(jobId, submit), 9L to pointer(jobId, move)))
        val store = BoardStoreElement(wal, cas)
        try {
            store.open()
            val row = checkNotNull(store.card(jobId))
            assertEquals(2L, row.revision)
            assertEquals(BoardCol.RUNNING, row.col)
            assertEquals("Legacy title", row.title)
            assertEquals(move, row.commandCid)
            assertEquals(9L, store.lastSequence)
            assertFalse(wal.closed)
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to "next",
                "expectedRevision" to 2, "toColumn" to "ready"), reply))
            assertEquals(10L, assertIs<BoardApply.Committed>(reply.await()).sequence)
            assertEquals(1, wal.flushes)
        } finally { store.drain() }
        assertTrue(wal.closed)
    }

    private class BufferedCas(
        private val calls: MutableList<String>,
        private val beforeSync: () -> Unit,
        private val syncFailure: Throwable? = null,
    ) : CasStore() {
        val pending = mutableSetOf<ContentId>()
        val durable = mutableSetOf<ContentId>()

        override fun put(bytes: ByteArray): ContentId = super.put(bytes).also {
            calls.add("put")
            pending.add(it)
        }

        override fun sync() {
            calls.add("sync")
            beforeSync()
            syncFailure?.let { throw it }
            durable.addAll(pending)
            pending.clear()
        }
    }

    private suspend fun queue(store: BoardStoreElement, jobId: String): CompletableDeferred<BoardApply> =
        CompletableDeferred<BoardApply>().also { reply ->
            store.intake.send(BoardIntake(mapOf(
                "type" to "submit", "jobId" to jobId, "idempotencyKey" to "submit/$jobId",
            ), reply))
        }

    @Test
    fun oneCasSyncPrecedesWalFlushAndBatchVisibility() = runTest {
        val calls = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<BoardApply>>()
        lateinit var store: BoardStoreElement
        fun hidden() {
            assertTrue(store.cards().isEmpty())
            assertEquals(0L, store.lastSequence)
            assertEquals(0, store.projection().cardCount)
            assertTrue(replies.none { it.isCompleted })
        }
        val cas = BufferedCas(calls, ::hidden)
        val records = mutableListOf<ContentId>()
        val wal = object : BoardWalPort {
            override fun append(record: ByteArray): Long {
                hidden()
                calls.add("append")
                records.add(ContentId(record.decodeToString().substringAfter('\t')))
                assertTrue(cas.durable.isEmpty())
                return records.size.toLong()
            }

            override fun flush() {
                hidden()
                calls.add("flush")
                assertTrue(cas.pending.isEmpty())
                assertEquals(records.toSet(), cas.durable)
            }

            override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) = Unit
        }
        store = BoardStoreElement(wal, cas)
        replies.add(queue(store, "one"))
        replies.add(queue(store, "two"))
        try {
            store.open()
            replies.forEach { assertIs<BoardApply.Committed>(it.await()) }
            assertEquals(listOf("put", "append", "put", "append", "sync", "flush"), calls)
            assertEquals(setOf("one", "two"), store.cards().map { it.jobId }.toSet())
            assertEquals(2L, store.lastSequence)
        } finally { store.drain() }
    }

    @Test
    fun casSyncFailureClosesAdmissionWithoutWalFlushOrAcknowledgment() = runTest {
        failedBarrier(sync = true)
    }

    @Test
    fun walFlushFailureClosesAdmissionAfterCasSyncWithoutAcknowledgment() = runTest {
        failedBarrier(sync = false)
    }

    private suspend fun failedBarrier(sync: Boolean) {
        val calls = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<BoardApply>>()
        val failure = IllegalStateException("injected ${if (sync) "CAS sync" else "WAL flush"} failure")
        lateinit var store: BoardStoreElement
        fun hidden() {
            assertTrue(store.cards().isEmpty())
            assertEquals(0L, store.lastSequence)
            assertEquals(0, store.projection().cardCount)
            assertTrue(replies.none { it.isCompleted })
        }
        val cas = BufferedCas(calls, ::hidden, failure.takeIf { sync })
        val wal = object : BoardWalPort {
            var sequence = 0L
            override fun append(record: ByteArray): Long {
                calls.add("append")
                return ++sequence
            }

            override fun flush() {
                hidden()
                calls.add("flush")
                assertEquals(2, cas.durable.size)
                throw failure
            }

            override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) = Unit
        }
        store = BoardStoreElement(wal, cas)
        replies.add(queue(store, "one"))
        replies.add(queue(store, "two"))
        try {
            store.open()
            replies.forEach {
                assertEquals(failure.message, assertFailsWith<IllegalStateException> { it.await() }.message)
            }
            assertEquals(listOf("put", "append", "put", "append", "sync") +
                if (sync) emptyList() else listOf("flush"), calls)
            assertTrue(store.cards().isEmpty())
            assertEquals(0L, store.lastSequence)
            assertEquals(0, store.projection().cardCount)
            assertSame(failure, store.failure)
            assertTrue(store.intake.trySend(BoardIntake(emptyMap<String, Any?>())).isFailure)
        } finally { store.drain() }
    }
}
