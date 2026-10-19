package modelmux

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BreadcrumbElementTest {
    @Test fun restartLoadsStateOnceWithoutRefreshingAccessOrWritingOnInspection() = runTest {
        val store = Store()
        val first = BreadcrumbElement({ BreadcrumbContext({ 0L }, BreadcrumbRetention(1_000, 1_000)) }, store = store)
        val emitted = BreadcrumbId("retrieved")
        val raw = """{"role":"tool", "tool_call_id":"call", "content":[{"type":"text","text":"exact evidence"}]}"""
        val id = first.transact("42") {
            val record = it.put("read", "0123456789", BreadcrumbTimestamp(10))
            it.resize(record.id, BreadcrumbPresence(0.7), BreadcrumbTimestamp(20))
            it.reserveEmissions(setOf(emitted))
            it.completeEmissions(setOf(emitted), successful = true)
            it.submit(BreadcrumbSubmission("fixture/model", BreadcrumbTimestamp(30), s_[raw], 1, true))
            record.id
        }
        val saved = store.rows.getValue("42")
        var clockReads = 0
        val restored = BreadcrumbElement({ BreadcrumbContext({ clockReads++; 1_020L }) }, store = store)
        assertEquals(1, store.loads, "Construction must not load storage")
        assertEquals("42", restored.snapshot()[0]["context"])
        repeat(2) {
            restored.transact("42", create = false) {
                assertEquals(saved, it.state())
                assertEquals(BreadcrumbTimestamp(20), it.lookup(id)!!.lastAccessAt)
                assertEquals("0123456789", it.lookup(id)!!.content)
                assertTrue(it.emitted(emitted))
                assertEquals(raw, it.submission!!.messages[0])
            }
        }
        assertEquals(0, clockReads, "Hydration and metadata reads must not consult the clock")
        assertEquals(2, store.loads)
        assertEquals(1, store.writes.size, "Inspection must not rewrite durable context state")
        val override = restored.transact("42", create = false) { it.nextToolResult(BreadcrumbTimestamp(1_020)) }
        assertEquals("0123456", override[0].content)
        assertEquals(2, store.writes.size)
        assertEquals("breadcrumb-2", restored.transact("42") { it.put("read", "next").id.value })
        val restarted = BreadcrumbElement(store = store)
        restarted.transact("42", create = false) {
            assertTrue(it.snapshot(BreadcrumbTimestamp(1_020))[0].presence.value < 0.7)
            assertEquals(raw, it.submission!!.messages[0])
            assertEquals(BreadcrumbTimestamp(20), it.lookup(id)!!.lastAccessAt)
        }
    }

    @Test fun admittedWriteCompletesBeforeTransactionReturnsAndCancellationDrainsIt() = runTest {
        val store = Store()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeWrite = { started.complete(Unit); release.await() }
        val contexts = BreadcrumbElement(store = store)
        val caller = launch { contexts.transact("session") { it.put("read", "evidence") } }
        started.await()
        assertFalse(caller.isCompleted)
        assertTrue(store.rows.isEmpty())
        caller.cancel()
        yield()
        assertFalse(caller.isCompleted, "Cancellation must await an already admitted durable write")
        release.complete(Unit)
        caller.join()
        assertTrue(caller.isCancelled)
        assertTrue(caller.children.none { it.isActive })
        assertEquals("evidence", BreadcrumbElement(store = store).transact("session", create = false) {
            it.lookup(BreadcrumbId("breadcrumb-1"))!!.content
        })
    }

    @Test fun failedSaveRollsBackSameContextIncludingActiveReservations() = runTest {
        val store = Store()
        val contexts = BreadcrumbElement({ BreadcrumbContext({ 0L }) }, store = store)
        val reserved = BreadcrumbId("in-flight")
        val context = contexts.transact("session") {
            it.put(BreadcrumbId("original"), "read", "0123456789")
            it.reserveEmissions(setOf(reserved))
            it
        }
        val before = context.state()
        val failure = IllegalStateException("write failed")
        store.failure = failure
        val thrown = assertFailsWith<IllegalStateException> {
            contexts.transact("session") {
                it.completeEmissions(setOf(reserved), successful = true)
                it.resize(BreadcrumbId("original"), BreadcrumbPresence(0.1), BreadcrumbTimestamp(500))
                it.put("read", "uncommitted")
                it.submit(BreadcrumbSubmission("uncommitted", BreadcrumbTimestamp(500),
                    s_["""{"role":"tool","tool_call_id":"call","content":"uncommitted"}"""], 1, true))
            }
        }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure },
            "Coroutine recovery must retain the original persistence failure")
        assertEquals(before, store.rows.getValue("session"))
        contexts.transact("session", create = false) {
            assertSame(context, it, "In-flight completion cleanup relies on context identity")
            assertEquals(before, it.state())
            assertFalse(it.emitted(reserved))
            it.completeEmissions(setOf(reserved), successful = false)
        }
        assertEquals(1, store.writes.size)
        store.failure = null
        assertEquals("breadcrumb-1", contexts.transact("session") { it.put("read", "committed").id.value })
    }

    @Test fun failedOperationAndFailedAdmissionLeaveNoStateOrCapacityBehind() = runTest {
        val store = Store()
        val contexts = BreadcrumbElement({ BreadcrumbContext({ 0L }) }, capacity = 1, store = store)
        store.failure = IllegalStateException("write failed")
        assertFailsWith<IllegalStateException> { contexts.transact("failed") { it.put("read", "discarded") } }
        assertEquals(0, contexts.snapshot().size)
        store.failure = null
        contexts.transact("retained") { it.put("read", "original") }
        val saved = store.rows.getValue("retained")
        assertFailsWith<IllegalArgumentException> {
            contexts.transact("retained") {
                it.put("read", "discarded")
                throw IllegalArgumentException("operation failed")
            }
        }
        assertEquals(saved, contexts.transact("retained") { it.state() })
        assertEquals(1, store.writes.size)
        assertFailsWith<BreadcrumbCapacityException> { contexts.transact("second") { it.put("read", "extra") } }
    }

    @Test fun deletionIsDurableAndFailedDeleteKeepsTheSession() = runTest {
        val store = Store()
        val contexts = BreadcrumbElement({ BreadcrumbContext({ 0L }) }, store = store)
        contexts.transact("selected") { it.put("read", "selected evidence") }
        contexts.transact("other") { it.put("read", "other evidence") }
        val other = store.rows.getValue("other")
        store.failure = IllegalStateException("delete failed")
        assertFailsWith<IllegalStateException> { contexts.remove("selected") }
        assertEquals(2, contexts.snapshot().size)
        assertTrue("selected" in store.rows)
        store.failure = null
        contexts.remove("selected")
        assertNull(store.writes.last().b)
        assertEquals("selected", store.writes.last().a)
        assertEquals(other, store.rows.getValue("other"))
        val restored = BreadcrumbElement(store = store)
        assertEquals("other", restored.snapshot()[0]["context"])
        assertFailsWith<IllegalArgumentException> { restored.transact("selected", create = false) {} }
        assertEquals(3, store.writes.size)
    }

    @Test fun concurrentUpdatesAwaitStorageAndRetainEveryRecord() = runTest {
        val store = Store()
        store.beforeWrite = { yield() }
        val contexts = BreadcrumbElement({ BreadcrumbContext({ 0L }) }, store = store)
        val ids = (0 until 32).map { index ->
            async { contexts.transact("shared") { it.put("read:$index", "evidence:$index").id } }
        }.awaitAll()
        assertEquals(32, ids.toSet().size)
        assertEquals(32, store.writes.size)
        assertTrue(store.writes.all { it.a == "shared" })
        val restored = BreadcrumbElement({ BreadcrumbContext({ 0L }) }, store = store)
        assertEquals((0 until 32).map { "evidence:$it" }.toSet(),
            restored.transact("shared", create = false) { it.snapshot().view.map { row -> row.content }.toSet() })
    }

    @Test fun hydrationFailurePublishesNothingAndCanBeRetried() = runTest {
        val state = BreadcrumbContext({ 0L }).also { it.put("read", "evidence") }.state()
        val store = Store()
        store.rows["valid"] = state
        store.rows["invalid"] = "not a context"
        val contexts = BreadcrumbElement(store = store)
        assertFails { contexts.snapshot() }
        assertEquals(0, store.writes.size)
        store.rows.clear()
        store.rows["replacement"] = state
        assertEquals(1, contexts.snapshot().size)
        assertEquals("replacement", contexts.snapshot()[0]["context"])
        assertEquals(2, store.loads)
        assertFailsWith<IllegalArgumentException> { contexts.transact("valid", create = false) {} }
        assertEquals(0, store.writes.size)
    }

    private class Store : BreadcrumbStore {
        val rows = linkedMapOf<String, String>()
        val writes = mutableListOf<Join<String, String?>>()
        var loads = 0
        var failure: Throwable? = null
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun load(): Series<Join<String, String>> {
            loads++
            return rows.entries.map { it.key j it.value }.toSeries()
        }

        override suspend fun write(id: String, state: String?) {
            beforeWrite()
            failure?.let { throw it }
            if (state == null) rows.remove(id) else rows[id] = state
            writes += id j state
        }
    }
}
