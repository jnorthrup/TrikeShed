package modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import modelmux.acp.*
import kotlin.test.*

class BreadcrumbContextTest {
    @Test fun ageAndRecencyDecayIndependentlyWithoutRenderingRefreshingAccess() {
        var now = 0L
        val context = BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000))
        val record = context.put("tool/search", "a".repeat(100))
        now = 1_000
        assertEquals(0.25, context.snapshot()[0].presence.value)
        assertEquals(25, context.snapshot()[0].content.length)
        repeat(3) { context.nextToolResult().breadcrumbJson() }
        assertEquals(BreadcrumbTimestamp(0), context.lookup(record.id)!!.lastAccessAt)

        context.resize(record.id, BreadcrumbPresence(1.0))
        assertEquals(1.0, context.nextToolResult()[0].presence.value)
        assertEquals(0.5, context.nextToolResult()[0].presence.value, "Explicit access refreshes recency, but age remains")
        now = 2_000
        assertEquals(0.125, context.snapshot()[0].presence.value)
    }

    @Test fun collapsedEvidenceCanBeRetrievedFullyOnceAndPartialPresenceIsProportional() {
        var now = 0L
        val context = BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000))
        val record = context.put("tool/read", "0123456789")
        now = 100_000
        val collapsed = context.nextToolResult()[0]
        assertEquals(0.0, collapsed.presence.value)
        assertEquals(record.id.value, reifyMap(collapsed.render())["id"])
        assertFalse("content" in reifyMap(collapsed.render()))

        assertTrue(context.resize(record.id, BreadcrumbPresence(1.0)))
        val result = context.nextToolResult()
        assertEquals(0.0, context.nextToolResult()[0].presence.value)
        assertEquals("0123456789", result[0].content, "A lazy result keeps its consumed override")
        context.resize(record.id, BreadcrumbPresence(0.3))
        assertEquals("012", context.nextToolResult()[0].content)
        context.resize(record.id, BreadcrumbPresence(0.0))
        assertEquals("", context.nextToolResult()[0].content)
        assertEquals("0123456789", context.lookup(record.id)!!.content)
    }

    @Test fun reingestionPreservesTimeAndOriginalEvidence() {
        var now = 10L
        val context = BreadcrumbContext({ now })
        val id = BreadcrumbId("call-1")
        val original = context.put(id, "read", "original")
        now = 100L
        assertEquals(original, context.put(id, "read", "original"))
        assertFailsWith<IllegalArgumentException> { context.put(id, "read", "replacement") }
        assertEquals(original, context.lookup(id))
    }

    @Test fun snapshotNeitherConsumesOverridesNorChangesAfterMutation() {
        var now = 0L
        val context = BreadcrumbContext({ now }, BreadcrumbRetention(1_000, 1_000))
        val record = context.put("read", "original")
        now = 1_000
        context.resize(record.id, BreadcrumbPresence(0.0))
        val snapshot = context.snapshot()
        now = 2_000
        context.put("new", "evidence")
        assertEquals(1, snapshot.size)
        assertEquals(0.5, snapshot[0].presence.value)
        assertEquals(0.0, context.nextToolResult()[0].presence.value)
        assertTrue(context.nextToolResult()[0].presence.value > 0.0)
    }

    @Test fun clockRegressionDoesNotAmplifyPresenceOrMoveAccessBackwards() {
        var now = 100L
        val context = BreadcrumbContext({ now })
        val record = context.put("read", "original")
        now = 50L
        assertEquals(1.0, context.snapshot()[0].presence.value)
        context.resize(record.id, BreadcrumbPresence(0.4))
        assertEquals(BreadcrumbTimestamp(100), context.lookup(record.id)!!.lastAccessAt)
    }

    @Test fun invalidPresenceCannotMutateContext() {
        val context = BreadcrumbContext({ 0L })
        val record = context.put("read", "original")
        for (value in doubleArrayOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { BreadcrumbPresence(value) }
        }
        assertFalse(context.resize(BreadcrumbId("missing"), BreadcrumbPresence(1.0)))
        assertEquals(record, context.lookup(record.id))
    }

    @Test fun dispatcherValidatesBeforeConsumptionAndReturnsRetrievableReferences() {
        val context = BreadcrumbContext({ 0L })
        val record = context.put("read\n\"\\\u0001", "abcdefghij")
        val dispatcher = BreadcrumbToolDispatcher(context)
        context.resize(record.id, BreadcrumbPresence(0.2))
        for (value in arrayOf("NaN", "Infinity", "-0.1", "1.1", "oops")) {
            assertTrue("error" in reifyMap(dispatcher.dispatch(TOOL_BREADCRUMB, mapOf("id" to record.id.value, "presence" to value))!!))
        }
        assertEquals("ab", context.nextToolResult()[0].content)
        assertNull(dispatcher.dispatch("other", emptyMap()))
        val response = reifyMap(dispatcher.dispatch(TOOL_BREADCRUMB, mapOf("id" to record.id.value, "presence" to "0"))!!)
        val projection = (response["breadcrumbs"] as List<*>).single() as Map<*, *>
        assertEquals(record.id.value, projection["id"])
        assertEquals(record.source, projection["source"])
        assertFalse("content" in projection)
        assertEquals("abcdefghij", context.nextToolResult()[0].content)
    }

    @Test fun fractionalPrefixPreservesUnicodeCodePointBoundaries() {
        val context = BreadcrumbContext({ 0L })
        val record = context.put("read", "A\uD83D\uDE00B")
        assertEquals("A", record.project(BreadcrumbPresence(0.5)).content)
        assertEquals("A\uD83D\uDE00", record.project(BreadcrumbPresence(0.75)).content)
        assertEquals(record.content, record.project(BreadcrumbPresence(1.0)).content)
    }
}
