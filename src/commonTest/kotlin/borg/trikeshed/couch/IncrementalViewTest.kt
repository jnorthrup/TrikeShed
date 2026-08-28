package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.ViewProgramLowering
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.cascade.fibTicks
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P2 gates: LCNC lowering parity, incremental=eager, checkpoint resume, logged Trie cache. */
class IncrementalViewTest {
    private fun db(): CouchDatabase {
        val cas = CasStore.inMemory()
        return CouchDatabase("inc", CouchStoreFactory.casBacked(cas), cas)
    }

    private fun program(reducer: String = "_sum"): LcncProgram {
        val nodes = arrayOf(
            LcncNode("emit", LcncContracts.VIEW_EMIT, mapOf(
                "ddoc" to "_design/test", "view" to "by_type",
                "key" to "doc.type", "value" to "doc.qty",
            )),
            LcncNode("reduce", LcncContracts.VIEW_REDUCE, mapOf("reducer" to reducer)),
        )
        return LcncProgram("by_type", nodes.size j { i: Int -> nodes[i] }, emptySeriesOf())
    }

    private fun canonical(result: ViewResult): List<String> {
        val out = ArrayList<String>(result.size)
        for (row in result.rows) out.add("${row.key}=${row.value}")
        return out.sorted()
    }

    private fun live(db: CouchDatabase): List<Document> =
        db.store.all().filter { !db.isTombstone(it) && !it.id.startsWith("_design/") }

    @Test
    fun lcncDefinedViewEqualsHandBuiltDefinition() {
        val lowered = ViewProgramLowering.lower(program())
        val hand = ViewDefinition(
            "_design/test", "by_type",
            MapFunction.Emit(KeyExpr.DocField("type"), ValueExpr.DocField("qty")),
            ReduceFunction.Builtin("_sum"),
        )
        val docs = listOf(
            Document("a", listOf(Field("type", "widget"), Field("qty", 2))),
            Document("b", listOf(Field("type", "widget"), Field("qty", 3))),
            Document("c", listOf(Field("type", "gadget"), Field("qty", 7))),
        )
        assertEquals(canonical(ViewServer().execute(hand, docs)), canonical(ViewServer().execute(lowered, docs)))
        assertEquals("_design/test/by_type", lowered.fullName)
    }

    @Test
    fun incrementalEqualsEagerAtEveryFibTickAfterMutations() = runTest {
        val db = db()
        val def = ViewProgramLowering.lower(program())
        val logs = mutableListOf<String>()
        val inc = IncrementalViewElement(db, def, logs::add)
        val ticks = fibTicks(24).let { s -> (0 until s.size).map { s[it] }.toSet() }

        for (i in 0 until 24) {
            db.put("d$i", mapOf("type" to if (i % 3 == 0) "gadget" else "widget", "qty" to i + 1), null)
            inc.drainFrames()
            if (i in ticks) {
                val eager = ViewServer().execute(def, live(db))
                assertEquals(canonical(eager), canonical(inc.answer()), "fibTick=$i")
            }
        }
        // Update and delete are replacement/retraction, never duplication.
        val rev0 = db.store.head.getRev("d0")
        db.put("d0", mapOf("type" to "widget", "qty" to 100), rev0)
        val rev1 = db.store.head.getRev("d1")
        db.delete("d1", rev1)
        inc.drainFrames()
        assertEquals(canonical(ViewServer().execute(def, live(db))), canonical(inc.answer()))
        assertTrue(logs.isNotEmpty() && logs.last().contains("cache="), "cache decision must be logged")
        assertEquals(16, inc.cachedPrefixCount("widget"), "Trie prefix count reflects current widget emissions")
    }

    @Test
    fun restartRestoresMappedCacheAndDrainsOnlyNewFrames() = runTest {
        val db = db()
        val def = ViewProgramLowering.lower(program("_count"))
        for (i in 0 until 40) db.put("d$i", mapOf("type" to if (i % 2 == 0) "even" else "odd", "qty" to i), null)
        val first = IncrementalViewElement(db, def)
        first.drainFrames()
        assertEquals(40L, first.framesDrained)
        val before = canonical(first.answer())

        // New element = process/component restart. It restores rows + sequence from _local.
        val restarted = IncrementalViewElement(db, def)
        assertEquals(before, canonical(restarted.answer()), "cached reducer state survives restart")
        db.put("d40", mapOf("type" to "even", "qty" to 40), null)
        restarted.drainFrames()
        assertEquals(1L, restarted.framesDrained, "resume drains one new frame, not the 41-doc corpus")
        assertEquals(canonical(ViewServer().execute(def, live(db))), canonical(restarted.answer()))
    }

    @Test
    fun rollupCountIsBoundedAndRereduceShaped() {
        val def = ViewProgramLowering.lower(program("rollup-count"))
        val docs = listOf(
            Document("a", listOf(Field("type", "x"), Field("qty", 2))),
            Document("b", listOf(Field("type", "x"), Field("qty", 3))),
        )
        val result = ViewServer().execute(def, docs)
        assertEquals(1, result.size)
        assertEquals(listOf(5.0, 2L), result[0].value, "fixed [rollup,count] shape")
    }

    @Test
    fun routerUsesIncrementalRegistryOnlyForExplicitlyMarkedView() = runTest {
        val db = db()
        val def = ViewProgramLowering.lower(program())
        db.put("a", mapOf("type" to "widget", "qty" to 2), null)
        db.put("b", mapOf("type" to "widget", "qty" to 3), null)
        db.put("_design/test", mapOf(
            "views" to mapOf("by_type" to mapOf(
                "map" to mapOf("key" to "type", "value" to "qty"),
                "reduce" to "_sum", "incremental" to true,
            )),
        ), null)
        val inc = IncrementalViewElement(db, def)
        inc.drainFrames()
        val router = CouchWireRouter(db, "projects/test/") { ddoc, view ->
            if (ddoc == "_design/test" && view == "by_type") inc else null
        }
        val reply = router.handle("GET", "/inc/_design/test/_view/by_type", ByteArray(0))!!
        assertEquals(200, reply.status)
        @Suppress("UNCHECKED_CAST")
        val body = JsonSupport.parse(reply.bytes.decodeToString()) as Map<String, Any?>
        assertEquals(true, body["incremental"], "route proves it used the registered element")
        val rows = CouchDatabase.asList(body["rows"])!!
        assertEquals(1, rows.size)
        assertTrue(rows[0].toString().contains("5.0"))
    }
}
