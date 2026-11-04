package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.modelmux.acp.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BreadcrumbForegroundTest {
    private val parent = BreadcrumbId("summary")
    private val child = BreadcrumbId("source")
    private val budget = BreadcrumbBudget(contextTokens = 16_384)

    private fun context(): BreadcrumbContext = BreadcrumbContext(clock = { 10L }).also {
        it.put(child, "document", "Retained source with the exact cobalt orchard evidence.")
        it.put(parent, "summary:heuristic", "Review the source breadcrumb.", children = _l[child])
    }

    private fun recall(context: BreadcrumbContext, id: BreadcrumbId, presence: String = "1"): Map<*, *> {
        val indexed = requireNotNull(context.index[id])
        return reifyStrict(requireNotNull(BreadcrumbToolDispatcher(context, budget).dispatch(TOOL_RECALL,
            _m["ref" j indexed.ref.value, "cid" j indexed.cid.value, "presence" j presence]))) as Map<*, *>
    }

    @Test fun childRecallForegroundsTheChildAndThinsItsHistoricalParent() {
        val context = context()
        recall(context, child)
        val initial = context.snapshot().view.associateBy { it.id }
        assertTrue(initial.getValue(child).foreground)
        assertFalse(initial.getValue(parent).children.single().foreground)
        assertEquals(0.0, initial.getValue(parent).children.single().presence.value)
        recall(context, parent)
        val parentRecord = requireNotNull(context.lookup(parent))
        val childRecord = requireNotNull(context.lookup(child))
        val reply = recall(context, child)
        assertEquals(child.value, reply["id"])
        assertEquals(childRecord.content.length.toDouble(), reply["selectedChars"])
        assertEquals(child, context.foreground?.a)
        val rows = context.snapshot().view.associateBy { it.id }
        assertTrue(rows.getValue(child).foreground)
        assertTrue(rows.getValue(child).root)
        assertEquals(childRecord.content, rows.getValue(child).content)
        val historical = rows.getValue(parent)
        assertEquals(0.0, historical.presence.value)
        assertEquals("", historical.content)
        assertEquals(child, historical.children.single().id)
        assertEquals(0.0, historical.children.single().presence.value)
        assertEquals(parentRecord.content, context.lookup(parent)?.content)
        assertEquals(_l[child], context.lookup(parent)?.children)
        assertEquals(ContentId.of(parentRecord.content.encodeToByteArray()), historical.cid)
        context.clean(0)
        assertEquals(0.0, context.snapshot().view.first { it.id == parent }.presence.value)
        recall(context, parent)
        assertEquals(parent, context.foreground?.a)
        assertEquals(parentRecord.content, context.snapshot().view.first { it.id == parent }.content)
        assertEquals(0.0, context.snapshot().view.first { it.id == child }.presence.value)
    }

    @Test fun failedMetadataAndBudgetOnlyRecallKeepTheCurrentForeground() {
        val context = context()
        recall(context, parent)
        recall(context, child, "0")
        assertEquals(parent, context.foreground?.a)
        assertEquals(false, context.access(BreadcrumbId("missing")))
        assertEquals(parent, context.foreground?.a)
        val dispatcher = BreadcrumbToolDispatcher(context, budget)
        assertNotNull(dispatcher.dispatch(TOOL_RECALL, _m["ref" j "ffff", "presence" j "1"]))
        val indexed = requireNotNull(context.index[child])
        BreadcrumbToolDispatcher(context, BreadcrumbBudget(contextTokens = 1)).dispatch(TOOL_RECALL,
            _m["ref" j indexed.ref.value, "cid" j indexed.cid.value, "presence" j "1"])
        assertEquals(parent, context.foreground?.a)
        assertTrue(context.resize(child, BreadcrumbPresence(0.0)))
        context.nextToolResult()
        assertEquals(parent, context.foreground?.a)
    }

    @Test fun storedForegroundRehydratesWithRawEvidenceAndLineageInItsOwnContext() = runBlocking {
        val states = mutableMapOf<String, String>()
        val store = object : BreadcrumbStore {
            override suspend fun load(): Series<Join<String, String>> = states.entries.map { it.key j it.value }.toSeries()
            override suspend fun write(id: String, state: String?) {
                if (state == null) states.remove(id) else states[id] = state
            }
        }
        val first = BreadcrumbElement(store = store)
        first.transact("own") { context ->
            context.put(child, "document", "Exact original source.")
            context.put(parent, "summary:heuristic", "Source summary.", children = _l[child])
            recall(context, parent)
            recall(context, child, "0.5")
        }
        first.transact("foreign") { context ->
            context.put(parent, "document", "Foreign raw evidence.")
            context.access(parent)
        }
        val restarted = BreadcrumbElement(store = store)
        restarted.transact("own", create = false) { context ->
            assertEquals(child, context.foreground?.a)
            assertEquals(0.5, context.foreground?.b?.value)
            assertEquals("Exact original source.", context.lookup(child)?.content)
            assertEquals(_l[child], context.lookup(parent)?.children)
            assertEquals(0.0, context.snapshot().view.first { it.id == parent }.presence.value)
            assertEquals(child, context.snapshot().view.first { it.foreground }.id)
            recall(context, parent)
            assertEquals(parent, context.foreground?.a)
        }
        restarted.transact("foreign", create = false) { context ->
            assertEquals(parent, context.foreground?.a)
            assertEquals("Foreign raw evidence.", context.lookup(parent)?.content)
            assertNull(context.lookup(child))
        }
    }

    @Test fun oldCheckpointWithoutNavigationLoadsAndIndependentRootsCanBeSelected() {
        val context = context()
        val encoded = reifyStrict(context.state()) as Map<*, *>
        val old = borg.trikeshed.parse.jsonOf(encoded.filterKeys { it != "foreground" && it != "history" })
        val restored = BreadcrumbContext.restore(old, clock = { 10L })
        assertNull(restored.foreground)
        recall(restored, parent)
        val other = restored.put(BreadcrumbId("independent"), "document", "Another retained root.")
        recall(restored, other.id)
        assertEquals(other.id, restored.foreground?.a)
        assertEquals(0.0, restored.snapshot().view.first { it.id == parent }.presence.value)
        assertEquals(_l[child], restored.lookup(parent)?.children)
    }
}
