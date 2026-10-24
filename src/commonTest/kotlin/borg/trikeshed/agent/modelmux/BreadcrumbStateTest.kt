package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.test.*

class BreadcrumbStateTest {
    @Test fun restartPreservesEvidenceTimeOverridesAndExactSubmission() {
        var now = 9_007_199_254_740_993L
        var clockReads = 0
        val clock = { clockReads++; now }
        val context = BreadcrumbContext(clock, BreadcrumbRetention(2_000, 500, BreadcrumbPresence(0.05)))
        val record = context.put("tool/read", "A\uD83D\uDE00B\uD800\\\n", BreadcrumbTimestamp(now))
        context.put("tool/search", "second", BreadcrumbTimestamp(now + 1))
        context.resize(record.id, BreadcrumbPresence(0.5), BreadcrumbTimestamp(now + 100))
        val emitted = BreadcrumbId("retrieval-success")
        val outstanding = BreadcrumbId("retrieval-outstanding")
        context.reserveEmissions(setOf(emitted))
        context.completeEmissions(setOf(emitted), true)
        context.reserveEmissions(setOf(outstanding))
        val row = """{ "role" : "tool", "tool_call_id":"call-1", "content":[{"type":"text","text":"\\u0041"},{"number":9007199254740993}] }"""
        context.submit(BreadcrumbSubmission("model", BreadcrumbTimestamp(now + 200), s_[row], 1, true))

        val state = context.state()
        assertEquals(0, clockReads, "Encoding must not inspect decay or refresh access")
        now += 1_000
        val restored = BreadcrumbContext.restore(state, clock)
        assertEquals(0, clockReads, "Restore must preserve recorded times")
        assertEquals(context.retention, restored.retention)
        assertEquals(context.lookup(record.id), restored.lookup(record.id))
        assertEquals(context.submittedJson("session"), restored.submittedJson("session"))
        assertEquals(row, restored.submission!!.messages[0], "JSON whitespace, escapes and integers remain exact")
        assertEquals(state, restored.state())
        assertEquals(context.snapshot().breadcrumbJson(), restored.snapshot().breadcrumbJson())
        assertEquals(BreadcrumbTimestamp(now - 900), restored.lookup(record.id)!!.lastAccessAt)
        assertEquals(0.5, restored.nextToolResult()[0].presence.value, "Inspection must not consume pending retrieval")
        assertNotEquals(0.5, restored.nextToolResult()[0].presence.value)
        assertTrue(restored.emitted(emitted))
        assertFalse(restored.emitted(outstanding))
        assertFailsWith<IllegalArgumentException> { restored.completeEmissions(setOf(outstanding), true) }
        restored.reserveEmissions(setOf(outstanding))
        restored.completeEmissions(setOf(outstanding), true)
        assertEquals("breadcrumb-3", restored.put("tool/next", "third").id.value)
    }

    @Test fun transactionCheckpointRestoresReservationsOnTheSameContext() {
        val context = BreadcrumbContext({ 10L })
        val record = context.put("tool/read", "original")
        val retrieval = BreadcrumbId("retrieval")
        context.resize(record.id, BreadcrumbPresence(0.25))
        context.reserveEmissions(setOf(retrieval))
        context.record(MuxCallRecord(1, model = "model", provider = "provider", startedAt = 5, endedAt = 10, status = "completed"))
        val checkpoint = context.copy()
        val state = checkpoint.state()
        context.nextToolResult()
        context.completeEmissions(setOf(retrieval), true)
        context.put("tool/new", "new")
        context.record(MuxCallRecord(2, model = "model", provider = "provider", startedAt = 6, endedAt = 11, status = "failed"))
        context.restore(checkpoint)
        assertEquals(state, context.state())
        assertFalse(context.emitted(retrieval))
        assertEquals(0.25, context.nextToolResult()[0].presence.value)
        context.completeEmissions(setOf(retrieval), true)
        assertTrue(context.emitted(retrieval))
        assertFalse(checkpoint.emitted(retrieval), "Mutable checkpoint state must be independent")
        assertEquals(0.25, checkpoint.nextToolResult()[0].presence.value)
        assertEquals(1, context.receipts().size)
    }

    @Test fun capacityAndCorruptStateFailBeforeAdmission() {
        val context = BreadcrumbContext({ 0L })
        context.put("tool/read", "original")
        val state = context.state()
        fun changed(field: String, value: Any?): String = jsonOf(reifyMap(state).toMutableMap().also { it[field] = value })
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("version", 2)) }
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("sequence", -1)) }
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("sequence", "-1")) }
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("pending", listOf(mapOf("id" to "missing", "presence" to 1)))) }
        val records = reifyMap(state)["records"] as List<*>
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("records", records + records)) }
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("emissions", List(1001) { "retrieval-$it" })) }
        val oversized = (records.single() as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
        oversized["content"] = "x".repeat(BreadcrumbContext.CONTENT_LIMIT + 1)
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("records", listOf(oversized))) }
        val invalid = mapOf("model" to "model", "atMs" to "0", "messageCount" to 1, "exact" to true, "messages" to emptyList<String>())
        assertFailsWith<IllegalArgumentException> { BreadcrumbContext.restore(changed("submission", invalid)) }
        assertEquals(state, context.state(), "Decode failures do not mutate the admitted instance")
    }

    @Test fun capacityMarkerAndNoSubmissionRemainDistinctAcrossRestart() {
        val context = BreadcrumbContext({ 0L })
        assertNull(BreadcrumbContext.restore(context.state()).submission)
        context.submit(BreadcrumbSubmission("model", BreadcrumbTimestamp(123), emptySeriesOf(), 1001, false))
        val restored = BreadcrumbContext.restore(context.state())
        assertEquals(context.submittedJson("session"), restored.submittedJson("session"))
        assertFalse(restored.submission!!.exact)
        assertEquals(1001, restored.submission!!.messageCount)
        assertEquals(0, restored.submission!!.messages.size)
    }

    @Test fun routingReceiptsRetainLongIdentityAndDeduplicateAfterRestart() {
        val context = BreadcrumbContext({ error("Receipt persistence must not read the clock") })
        val call = MuxCallRecord(
            id = 9_007_199_254_740_993L, conversationId = Long.MAX_VALUE, turnId = Long.MAX_VALUE - 1,
            assessmentId = "assessment", model = "model-a", provider = "provider", startedAt = 9_007_199_254_740_995L,
            endedAt = 9_007_199_254_740_996L, status = "completed", keyId = "llm.provider.key", httpStatus = 200,
            inputTokens = 12, outputTokens = 34, cachedHit = true, cacheReadTokens = 5, cacheWriteTokens = 6,
            contextId = "hermes-session",
        )
        context.record(call)
        val restored = BreadcrumbContext.restore(context.state())
        assertEquals(call, restored.receipts()[0])
        val retry = call.copy(status = "failed", error = "TimeoutCancellationException", httpStatus = 0)
        restored.record(retry)
        assertEquals(1, restored.receipts().size)
        assertEquals(retry, restored.receipts()[0])
        restored.record(call.copy(model = "model-b"))
        restored.record(call.copy(startedAt = call.startedAt + 1))
        assertEquals(3, restored.receipts().size, "Restarted counters and separate model journals must not collapse unrelated attempts")
        assertEquals(restored.state(), BreadcrumbContext.restore(restored.state()).state())
    }

    @Test fun routingRetentionRejectsActiveWorkAndReadsLegacyState() {
        val context = BreadcrumbContext({ 0L })
        val call = MuxCallRecord(1, model = "model", provider = "provider", startedAt = 0, contextId = "session")
        assertFailsWith<IllegalArgumentException> { context.record(call) }
        repeat(BreadcrumbContext.RECORD_LIMIT + 1) { index ->
            context.record(call.copy(id = index.toLong(), endedAt = 1, status = "completed"))
        }
        assertEquals(BreadcrumbContext.RECORD_LIMIT, context.receipts().size)
        assertEquals(1L, context.receipts()[0].id)
        val snapshot = context.receipts()
        context.record(call.copy(id = 1001, endedAt = 1, status = "completed"))
        assertEquals(1L, snapshot[0].id, "A routing read must remain stable after later writes")
        val state = reifyMap(context.state()).toMutableMap()
        state.remove("receipts")
        val restored = BreadcrumbContext.restore(jsonOf(state))
        assertEquals(0, restored.receipts().size, "The first unreleased v1 files lacked receipt history")
        val finished = context.receipts()[0]
        assertFailsWith<IllegalArgumentException> {
            context.record(finished.copy(model = "x".repeat(BreadcrumbContext.RECEIPT_CONTENT_LIMIT + 1)))
        }
        assertEquals(finished, context.receipts()[0])
    }
}
