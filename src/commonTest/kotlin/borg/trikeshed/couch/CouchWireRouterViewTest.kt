package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step A gate — the view engine is mounted on the daemon router, not only on the test-only
 * [borg.trikeshed.relaxfactory.CouchHttpSurface]. Everything `HtxLitebikeCouchE2eTest` proves
 * about `_view` (1.6.2 params: reduce/group/group_level/key/startkey/endkey/inclusive_end/
 * descending/skip/limit/include_docs, collation, rereduce, builtin reducers) must be answered
 * by [CouchWireRouter] over a CAS-backed [CouchDatabase] — the wiring the live daemon mounts
 * (`OroborosDaemon.kt` `router = CouchWireRouter(couchDb, …)`).
 */
class CouchWireRouterViewTest {

    private class Node {
        val cas: CasStore = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val db = CouchDatabase("trike", store, cas)
        val router = CouchWireRouter(db, PREFIX)

        @Suppress("UNCHECKED_CAST")
        suspend fun get(path: String): Pair<Map<String, Any?>, Int> {
            val reply = router.handle("GET", path, ByteArray(0))
                ?: error("router declined $path — _view must be answered by the daemon router")
            return JsonSupport.parse(reply.bytes.decodeToString()) as Map<String, Any?> to reply.status
        }

        suspend fun put(id: String, body: String) {
            val r = router.handle("PUT", "/trike/$id", body.encodeToByteArray())!!
            assertEquals(201, r.status, id)
        }

        @Suppress("UNCHECKED_CAST")
        fun rows(reply: Map<String, Any?>): List<Map<String, Any?>> =
            CouchDatabase.asList(reply["rows"])!!.map { it as Map<String, Any?> }
    }

    @Test
    fun daemonRouterAnswersViewQueriesWithReduceGroupAndRereduce() = runTest {
        val n = Node()
        // a=2 b=3 c=7 → widget sums 5, gadget 7 (the e2e fixture's shape, one doc lighter)
        n.put("a", """{"type":"widget","qty":2}""")
        n.put("b", """{"type":"widget","qty":3}""")
        n.put("c", """{"type":"gadget","qty":7}""")
        n.put(
            "_design/rf",
            """{"views":{
                "by_type":{"map":{"key":"type","value":"qty"},"reduce":"_sum"},
                "count_by_type":{"map":{"key":"type"},"reduce":"_count"},
                "by_qty":{"map":{"key":"qty","value":"doc"}}
            }}""",
        )

        // reduce=true&group=true → per-key sums
        val grouped = n.get("/trike/_design/rf/_view/by_type?reduce=true&group=true")
        assertEquals(200, grouped.second)
        assertEquals(
            mapOf("gadget" to 7.0, "widget" to 5.0),
            n.rows(grouped.first).associate { it["key"].toString() to (it["value"] as Number).toDouble() },
        )

        // group=true on a map-only key → per-key counts
        val counted = n.get("/trike/_design/rf/_view/count_by_type?group=true")
        assertEquals(
            mapOf("gadget" to 1L, "widget" to 2L),
            n.rows(counted.first).associate { it["key"].toString() to (it["value"] as Number).toLong() },
        )

        // group=false → single rereduced row with null key (1.6.2)
        val total = n.get("/trike/_design/rf/_view/by_type")
        assertEquals(1, n.rows(total.first).size)
        assertNull(n.rows(total.first)[0]["key"])
        assertEquals(12L, (n.rows(total.first)[0]["value"] as Number).toLong())
    }

    @Test
    fun daemonRouterAnswersRangeCollationAndPagingParams() = runTest {
        val n = Node()
        n.put("a", """{"type":"widget","qty":2}""")
        n.put("b", """{"type":"widget","qty":5}""")
        n.put("c", """{"type":"gadget","qty":7}""")
        n.put(
            "_design/rf",
            """{"views":{
                "by_type":{"map":{"key":"type","value":"qty"},"reduce":"_sum"},
                "by_qty":{"map":{"key":"qty","value":"doc"}}
            }}""",
        )

        // key + include_docs
        val widgets = n.get("/trike/_design/rf/_view/by_type?reduce=false&key=%22widget%22&include_docs=true")
        assertEquals(200, widgets.second)
        assertEquals(3L, (widgets.first["total_rows"] as Number).toLong())
        assertEquals(listOf("a", "b"), n.rows(widgets.first).map { it["id"] })
        assertEquals(listOf("widget", "widget"), n.rows(widgets.first).map { it["key"] })
        assertEquals(listOf(2L, 5L), n.rows(widgets.first).map { ((it["doc"] as Map<*, *>)["qty"] as Number).toLong() })

        // numeric collation: startkey/endkey, inclusive_end, descending, skip
        val mid = n.get("/trike/_design/rf/_view/by_qty?startkey=3&endkey=7")
        assertEquals(listOf("b", "c"), n.rows(mid.first).map { it["id"] })
        val exclusive = n.get("/trike/_design/rf/_view/by_qty?startkey=3&endkey=7&inclusive_end=false")
        assertEquals(listOf("b"), n.rows(exclusive.first).map { it["id"] })
        val desc = n.get("/trike/_design/rf/_view/by_qty?descending=true&limit=2")
        assertEquals(listOf("c", "b"), n.rows(desc.first).map { it["id"] })
        assertEquals(listOf(7L, 5L), n.rows(desc.first).map { (it["key"] as Number).toLong() })
        val skipped = n.get("/trike/_design/rf/_view/by_qty?skip=2")
        assertEquals(2L, (skipped.first["offset"] as Number).toLong())
        assertEquals(listOf("c"), n.rows(skipped.first).map { it["id"] })

        // reduce requested on a map-only view → 400; missing view → 404
        assertEquals(400, n.get("/trike/_design/rf/_view/by_qty?reduce=true").second)
        assertEquals(404, n.get("/trike/_design/rf/_view/nope").second)
    }

    @Test
    fun daemonRouterViewSeesOnlyLiveDocsAndDesignDocsStayExcluded() = runTest {
        val n = Node()
        n.put("a", """{"type":"widget","qty":1}""")
        n.put("_design/rf", """{"views":{"by_type":{"map":{"key":"type"},"reduce":"_count"}}}""")

        val counted = n.get("/trike/_design/rf/_view/by_type?group=true")
        // exactly the one live doc — the design doc itself must not be emitted
        assertEquals(
            mapOf("widget" to 1L),
            n.rows(counted.first).associate { it["key"].toString() to (it["value"] as Number).toLong() },
        )

        // tombstones drop out of the view
        val revA = n.store.head.getRev("a")!!
        val del = n.router.handle("DELETE", "/trike/a?rev=$revA", ByteArray(0))!!
        assertEquals(200, del.status)
        val after = n.get("/trike/_design/rf/_view/by_type?group=true")
        assertTrue(n.rows(after.first).isEmpty(), "tombstoned doc must leave the view")
    }

    companion object {
        const val PREFIX = "projects/trikeshed/"
    }
}
