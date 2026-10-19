package borg.trikeshed.kanban

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Metadata edits are versioned commits, not resubmissions or lifecycle transitions. */
class BoardCardUpdateTest {
    private fun update(revision: Any? = 1L, fields: Map<String, Any?> = mapOf("title" to "Edited")) =
        mapOf("type" to "update", "jobId" to "card", "idempotencyKey" to "edit",
            "expectedRevision" to revision) + fields

    private suspend fun send(store: BoardStoreElement, raw: Map<String, Any?>): BoardApply =
        CompletableDeferred<BoardApply>().let { reply ->
            store.intake.send(BoardIntake(raw, reply))
            reply.await()
        }

    private suspend fun create(store: BoardStoreElement) = assertIs<BoardApply.Committed>(send(store, mapOf(
        "type" to "submit", "jobId" to "card", "idempotencyKey" to "create", "title" to "Original",
        "priority" to 0, "tags" to listOf("curation"), "owner" to "human", "spec" to "REVIEW: human",
    )))

    @Test
    fun metadataHasCanonicalIdentityWithoutChangingLifecycleOrAttempts() {
        val reducer = JobReducer()
        val id = JobId("card")
        val dependency = JobId("dependency")
        assertTrue(reducer.reduce(JobCommand.Submit(dependency, "dependency")).accepted)
        assertTrue(reducer.reduce(JobCommand.Submit(id, "create", listOf(dependency))).accepted)
        assertTrue(reducer.reduce(JobCommand.Retry(id, "retry", 1)).accepted)
        assertTrue(reducer.reduce(JobCommand.Start(id, "start", 2)).accepted)
        val before = assertNotNull(reducer.snapshot(id))
        assertEquals(2, before.attemptCount)
        assertEquals("active", before.lifecycle)
        assertTrue(reducer.reduce(JobCommand.Fail(dependency, "failed-dependency", 1, "failure")).accepted)
        val command = assertIs<InvokeLowering.Outcome.Lowered>(InvokeLowering.lower(update(3))).command
        assertEquals("update", command.operationName)
        val encoded = CanonicalCbor.encode(command)
        assertEquals(command, CanonicalCbor.decodeJobCommand(encoded))
        val other = assertIs<InvokeLowering.Outcome.Lowered>(InvokeLowering.lower(
            update(3, mapOf("title" to "Different")))).command
        assertNotEquals(ContentId.of(encoded), ContentId.of(CanonicalCbor.encode(other)))
        assertTrue(reducer.reduce(command).accepted)
        assertEquals(before.copy(revision = 4), reducer.snapshot(id), "an edit must not rederive lifecycle from dependencies")
    }

    @Test
    fun metadataPreservesEveryColumnAndNonMetadataField() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            create(store)
            for (column in BoardCol.rendered) {
                val old = assertNotNull(store.card("card"))
                assertIs<BoardApply.Committed>(send(store, mapOf("type" to "move", "jobId" to "card",
                    "idempotencyKey" to "move-${column.wire}", "expectedRevision" to old.revision,
                    "toColumn" to column.wire, "atMs" to 10L)))
                val before = assertNotNull(store.card("card"))
                val projected = assertNotNull(store.projection().card("card"))
                val commit = assertIs<BoardApply.Committed>(send(store, update(before.revision,
                    mapOf("title" to "Edited ${column.wire}", "priority" to 2,
                        "idempotencyKey" to "edit-${column.wire}", "atMs" to 99L))))
                assertEquals(before.copy(title = "Edited ${column.wire}", priority = 2,
                    revision = commit.revision, lastSequence = commit.sequence, commandCid = commit.cid), store.card("card"))
                assertEquals(projected.copy(revision = commit.revision), store.projection().card("card"))
            }
        } finally { store.close() }
    }

    @Test
    fun staleUnknownAndMixedCommandsCannotMutateTheBoard() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            create(store)
            val original = assertNotNull(store.card("card"))
            val unknown = assertIs<BoardApply.Rejected>(send(store,
                update(0) + mapOf("jobId" to "missing", "idempotencyKey" to "missing")))
            assertTrue("unknown job" in unknown.reason, unknown.reason)
            assertNull(store.card("missing"))
            val stale = assertIs<BoardApply.Rejected>(send(store, update(0)))
            assertTrue("stale expectedRevision" in stale.reason, stale.reason)
            for (field in listOf("toColumn", "column", "owner", "dependencies", "parent", "strikes", "lcncRun", "spec")) {
                val result = assertIs<BoardApply.Rejected>(send(store, update() +
                    mapOf(field to "forbidden", "idempotencyKey" to "mixed-$field")))
                assertTrue(field in result.reason, result.reason)
            }
            assertEquals(original, store.card("card"))
            assertEquals(1L, store.lastSequence)
        } finally { store.close() }
    }

    @Test
    fun malformedOrEmptyMetadataIsRefusedInsteadOfDefaulted() {
        assertIs<InvokeLowering.Outcome.Lowered>(InvokeLowering.lower(update(1.0, mapOf("priority" to 1.0))))
        for (raw in listOf(update().minus("expectedRevision"), update(fields = emptyMap()),
            update(fields = mapOf("title" to "")), update(fields = mapOf("title" to 3)),
            update(fields = mapOf("title" to null)))) {
            assertIs<InvokeLowering.Outcome.Rejected>(InvokeLowering.lower(raw), raw.toString())
        }
        for (value in listOf<Any?>(null, "", "invalid", -1, 3, 1.5, Double.NaN, true)) {
            val rejected = assertIs<InvokeLowering.Outcome.Rejected>(InvokeLowering.lower(
                update(fields = mapOf("priority" to value))), "priority=$value")
            assertTrue("priority" in rejected.reason, rejected.reason)
        }
    }

    @Test
    fun revisionConversionCannotTruncateOrSaturateThePrecondition() {
        for (type in listOf("submit", "move", "update")) {
            val raw = mapOf("type" to type, "jobId" to "card", "idempotencyKey" to "revision") + when (type) {
                "move" -> mapOf("toColumn" to "ready")
                "update" -> mapOf("title" to "Edited")
                else -> emptyMap()
            }
            for (field in listOf("expectedRevision", "revision")) {
                for (value in listOf<Any>(1.5, 1.5f, Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Long.MAX_VALUE.toDouble(), Long.MIN_VALUE.toDouble() * 2,
                    "1.0", "invalid", false)) {
                    val rejected = assertIs<InvokeLowering.Outcome.Rejected>(
                        InvokeLowering.lower(raw + (field to value)), "$type $field=$value")
                    assertEquals("invalid expectedRevision", rejected.reason)
                }
                for ((value, expected) in listOf<Pair<Any, Long>>(1 to 1L, 1.0 to 1L, 1.0f to 1L,
                    4_294_967_296.0 to 4_294_967_296L, "1" to 1L, Long.MAX_VALUE to Long.MAX_VALUE,
                    Long.MAX_VALUE.toString() to Long.MAX_VALUE, Long.MIN_VALUE to Long.MIN_VALUE)) {
                    val command = assertIs<InvokeLowering.Outcome.Lowered>(
                        InvokeLowering.lower(raw + (field to value)), "$type $field=$value").command
                    val revision = when (command) {
                        is JobCommand.Submit -> command.expectedRevision
                        is JobCommand.Move -> command.expectedRevision
                        is JobCommand.Update -> command.expectedRevision
                        else -> error("unexpected command $command")
                    }
                    assertEquals(expected, revision, "$type $field=$value")
                }
            }
        }
    }

    @Test
    fun metadataDoesNotReleaseAClaimOrBypassReview() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            assertIs<BoardApply.Committed>(send(store, mapOf("type" to "submit", "jobId" to "card",
                "idempotencyKey" to "create", "owner" to "claim:worker")))
            assertIs<BoardApply.Committed>(send(store, mapOf("type" to "start", "jobId" to "card",
                "idempotencyKey" to "start", "expectedRevision" to 1)))
            assertIs<BoardApply.Committed>(send(store, update(2)))
            val row = assertNotNull(store.card("card"))
            assertEquals("claim:worker", row.owner)
            assertEquals(BoardCol.RUNNING, row.col)
            assertEquals("active", store.projection().card("card")?.lifecycle)
            val refused = assertIs<BoardApply.Rejected>(send(store, mapOf("type" to "move", "jobId" to "card",
                "idempotencyKey" to "settle", "expectedRevision" to 3, "toColumn" to "done")))
            assertTrue("review" in refused.reason, refused.reason)
            assertEquals(row, store.card("card"))
        } finally { store.close() }
    }

    @Test
    fun metadataCannotReplaceAnExecutionReceipt() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
        try {
            store.open()
            val id = "lcnc/run/root"
            assertIs<BoardApply.Committed>(send(store, mapOf("type" to "submit", "jobId" to id,
                "idempotencyKey" to "create", "owner" to "lcnc-runner",
                "lcncRun" to mapOf("runId" to "root", "executionFamily" to id))))
            val row = assertNotNull(store.card(id))
            val refused = assertIs<BoardApply.Rejected>(send(store, update() + ("jobId" to id)))
            assertTrue("receipt" in refused.reason, refused.reason)
            assertEquals(row, store.card(id))
        } finally { store.close() }
    }

    @Test
    fun partialEditsAndReplayRetainExactlyTheCommittedRow() = runTest {
        val records = mutableListOf<Pair<Long, ByteArray>>()
        val wal = object : BoardWalPort {
            override fun append(record: ByteArray): Long = (records.size + 1L).also { records.add(it to record) }
            override fun flush() = Unit
            override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) {
                for ((sequence, record) in records) onRecord(sequence, record)
            }
        }
        val cas = CasStore.inMemory()
        val store = BoardStoreElement(wal, cas, clock = { 7L })
        val expected: CardRow
        try {
            store.open()
            create(store)
            assertIs<BoardApply.Committed>(send(store, update()))
            assertEquals(0, store.card("card")?.priority, "omitted priority is not a default priority")
            assertIs<BoardApply.Committed>(send(store, update(2, mapOf("priority" to 1, "idempotencyKey" to "priority"))))
            expected = assertNotNull(store.card("card"))
            assertEquals("Edited", expected.title)
            assertEquals(1, expected.priority)
            assertEquals(7L, expected.lastMoveMs)
        } finally { store.close() }
        val reopened = BoardStoreElement(wal, cas, clock = { 999L })
        try {
            reopened.open()
            assertEquals(expected, reopened.card("card"))
            assertIs<BoardApply.Rejected>(send(reopened, update()))
            assertEquals(expected, reopened.card("card"))
        } finally { reopened.close() }
    }

    @Test
    fun lcncUpdateKeepsRevisionAndReportsTheStoreVerdictAndFreshSheets() = runTest {
        for (port in listOf("command", "command?")) {
            val store = BoardStoreElement(null, CasStore.inMemory(), clock = { 7L })
            try {
                store.open()
                create(store)
                val runner = assertNotNull(LcncKanbanExperience(store).registry()["kanban.update"])
                val node = LcncNode("edit", "kanban.update", mapOf("expectedRevision" to "99", "priority" to "2"))
                val accepted = runner.run(node, mapOf(port to update(1.0, mapOf("priority" to 1.0))))
                assertEquals(true, accepted["accepted"])
                assertEquals(2L, accepted["revision"])
                val stale = runner.run(node, mapOf(port to (update() + ("idempotencyKey" to "stale"))))
                assertEquals(false, stale["accepted"])
                assertTrue("stale expectedRevision" in stale["reason"].toString())
                val sheets = assertIs<Map<*, *>>(stale["sheets"])
                val row = assertIs<Map<*, *>>(assertIs<List<*>>(assertIs<Map<*, *>>(sheets["boardView"])["items"]).single())
                assertEquals("Original", row["title"])
                assertEquals(2L, row["revision"])
                val parameterEdit = runner.run(LcncNode("edit", "kanban.update", mapOf("jobId" to "card",
                    "title" to "From parameters", "expectedRevision" to "2", "idempotencyKey" to "parameters")), emptyMap())
                assertEquals(true, parameterEdit["accepted"])
                assertEquals(1, store.card("card")?.priority)
                val invalid = runner.run(node, mapOf(port to update(3, mapOf("title" to "Bad", "toColumn" to "done",
                    "idempotencyKey" to "mixed"))))
                assertEquals(false, invalid["accepted"], "the adapter cannot erase an invalid mixed command")
                assertEquals("From parameters", store.card("card")?.title)
                assertEquals(3L, store.card("card")?.revision)
            } finally { store.close() }
        }
    }

    @Test
    fun lcncUpdateContractDoesNotInventMetadataDefaults() {
        val contract = assertNotNull(LcncContracts.find("kanban.update"))
        assertTrue(contract.isEffect)
        for (field in listOf("title", "priority")) {
            val param = assertNotNull(contract.params[field])
            assertTrue(param.optional)
            assertEquals("", param.v)
        }
        assertFalse(assertNotNull(contract.params["expectedRevision"]).optional)
        assertFalse(assertNotNull(contract.params["idempotencyKey"]).optional)
    }
}
