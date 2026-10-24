package borg.trikeshed.agent.modelmux

import borg.trikeshed.collections.associative.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.agent.modelmux.acp.*
import kotlin.test.*

class BreadcrumbIndexTest {
    @Test fun collisionsRequireFullIdentityAndNeverReturnAnotherBlob() {
        val buckets = mutableMapOf<BreadcrumbRef, String>()
        var pair: Join<String, String>? = null
        for (index in 0 until 4000) {
            val text = "evidence-$index"
            val ref = BreadcrumbIndex.reference(ContentId.of(text.encodeToByteArray()))
            val previous = buckets[ref]
            if (previous != null) { pair = previous j text; break }
            buckets[ref] = text
        }
        val collision = assertNotNull(pair)
        val context = BreadcrumbContext()
        val first = context.put(BreadcrumbId("first"), "source:first", collision.a)
        val second = context.put(BreadcrumbId("second"), "source:second", collision.b)
        val a = context.index[first.id]!!
        val b = context.index[second.id]!!
        assertEquals(a.ref, b.ref)
        val ambiguous = reifyStrict(context.index.recall(a.ref, null, BreadcrumbPresence(1.0), BreadcrumbBudget())) as Map<*, *>
        assertTrue((ambiguous["error"] as String).contains("ambiguous"))
        val selected = reifyStrict(context.index.recall(a.ref, a.cid, BreadcrumbPresence(1.0), BreadcrumbBudget())) as Map<*, *>
        val ranges = selected["ranges"] as List<*>
        assertEquals(collision.a, (ranges.single() as Map<*, *>)["content"])
        assertEquals(a.cid.value, (ranges.single() as Map<*, *>)["cid"])
    }

    @Test fun macroRecallFollowsLineageWithinTheEntireSerializedBudget() {
        val context = BreadcrumbContext()
        val original = context.put(BreadcrumbId("source"), "source:code", "prefix\n" + "x".repeat(9000) + "\ncritical invariant\n")
        val summary = context.put(BreadcrumbId("summary"), "summary:micro", "A generated long title",
            children = listOf(original.id))
        val indexed = context.index[summary.id]!!
        val budget = BreadcrumbBudget(512, instructionTokens = 0, outputTokens = 0)
        val wire = context.index.recall(indexed.ref, indexed.cid, BreadcrumbPresence(1.0), budget, "critical")
        assertTrue(wire.length <= budget.chars)
        val recalled = reifyStrict(wire) as Map<*, *>
        assertTrue((recalled["omittedChars"] as Number).toInt() > 0)
        val ranges = recalled["ranges"] as List<*>
        assertTrue(ranges.any { (it as Map<*, *>)["content"].toString().contains("critical invariant") })
        for (range in ranges) {
            val row = range as Map<*, *>
            val start = (row["start"] as Number).toInt()
            val end = (row["end"] as Number).toInt()
            assertEquals(original.content.substring(start, end), row["content"])
            assertEquals(context.index[original.id]!!.cid.value, row["cid"])
        }
        val zero = reifyStrict(context.index.recall(indexed.ref, indexed.cid, BreadcrumbPresence(0.0), budget)) as Map<*, *>
        assertEquals(0, (zero["selectedChars"] as Number).toInt())
        assertEquals(0, (zero["ranges"] as List<*>).size)
        assertEquals(original.content, context.lookup(original.id)!!.content)
    }

    @Test fun typedHeritageAndReferencesSurviveCheckpointAndShareCas() {
        val cas = CasStore.inMemory()
        val context = BreadcrumbContext(index = BreadcrumbIndex(cas))
        val heritage = confixDoc(Cbor.encode(itemMapOf("bytes" to byteArrayOf(1, 2, 3).toItem(),
            "approved" to true.toItem(), "count" to 7.toItem())), Syntax.CBOR)
        val record = context.put(BreadcrumbId("record"), "source", "unchanged evidence",
            title = "Generated title carrying exact identifiers", heritage = heritage)
        val indexed = context.index[record.id]!!
        assertNotNull(context.index.facet(record.id, ConfixIndexK.TreeCursor))
        val restored = BreadcrumbContext.restore(context.state(), cas = cas)
        val after = restored.index[record.id]!!
        assertEquals(indexed.ref, after.ref)
        assertEquals(indexed.cid, after.cid)
        assertEquals(indexed.title, after.title)
        assertContentEquals(byteArrayOf(1, 2, 3), after.heritage!!.value("bytes") as ByteArray)
        assertEquals(true, after.heritage.value("approved"))
        val recalled = reifyStrict(restored.index.recall(after.ref, after.cid, BreadcrumbPresence(0.0),
            BreadcrumbBudget())) as Map<*, *>
        val nodes = ((recalled["heritage"] as Map<*, *>)["nodes"] as List<*>).map { it as Map<*, *> }
        assertTrue(nodes.any { it["tag"] == "IoBoolean" && it["value"] == true })
        val binary = nodes.single { it["tag"] == "IoBytes" }["value"] as Map<*, *>
        assertEquals("base64", binary["encoding"])
        assertEquals("AQID", binary["value"])
        val found = reifyStrict(BreadcrumbToolDispatcher(restored).dispatch(TOOL_SEARCH,
            mapOf("query" to "identifiers"))!!) as Map<*, *>
        assertEquals(indexed.cid.value, (((found["matches"] as List<*>).single()) as Map<*, *>)["cid"])
    }

    @Test fun advertisedToolsAreExecutableAndRecallRefreshesAccessWithoutChangingContent() {
        var now = 0L
        val context = BreadcrumbContext({ now })
        val record = context.put(BreadcrumbId("record"), "source", "precise evidence")
        val indexed = context.index[record.id]!!
        val request = confixDoc("""{"messages":[{"role":"user","content":"inspect memory"}]}""")
        val projected = BreadcrumbModelHost.project(request, context)
        assertEquals(listOf("breadcrumb", "recall", "search"),
            projected.getAt("tools")!!.kids.view.map { it.getAt("function", "name", src = projected.src)!!.reify(projected.src) })
        now = 1000
        val recalled = BreadcrumbToolDispatcher(context).dispatch(TOOL_RECALL, mapOf(
            "ref" to indexed.ref.value, "cid" to indexed.cid.value, "presence" to "1"))!!
        assertTrue(recalled.contains("precise evidence"))
        assertEquals(now, context.lookup(record.id)!!.lastAccessAt.value)
        assertEquals(record.content, context.lookup(record.id)!!.content)
    }
}
