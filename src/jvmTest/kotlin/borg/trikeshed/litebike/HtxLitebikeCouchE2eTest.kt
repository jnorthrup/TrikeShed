package borg.trikeshed.litebike

import borg.trikeshed.couch.ConfixDocStoreFactory
import borg.trikeshed.htx.HtxClientReactorElement
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.openHtxClientReactorElement
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.relaxfactory.CouchHttpSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end over a real loopback socket, every hop in-tree:
 *
 *   HtxClientReactorElement ── HtxReactorElement (JvmChannelOperations, real TCP)
 *     ─▶ JvmLitebikeBindAdapter (AsynchronousServerSocketChannel) ─▶ LitebikeListenerElement[Protocol.Http]
 *     ─▶ HTTP worker (request-line/header/body parse) ─▶ CouchHttpSurface (CouchDB 1.6.2 shape)
 *     ─▶ ConfixDocStore / ViewServer / CouchRequestFactory ─▶ reply through ConnectionRegistry.write
 *
 * Proves: document PUT/GET/DELETE with _rev discipline, design-doc view queries with
 * 1.6.2 params (reduce/group/key/startkey/endkey/include_docs/descending/limit), and the
 * RequestFactory batched envelope over POST /{db}.
 */
class HtxLitebikeCouchE2eTest {

    private val db = "trike"

    @Test
    fun htxClientThroughLitebikeIntoCouchSurface() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val store = ConfixDocStoreFactory.createSequential()
        val surface = CouchHttpSurface(db, store)

        val listener = LitebikeListenerElement().also { it.open() }
        val httpSlot = listener.register(Protocol.Http)
        val connections = ConnectionRegistry()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // HTTP worker: the seam JvmKanbanServer hand-rolls, here pointed at the couch surface.
        serverScope.launch {
            while (true) {
                val msg = httpSlot.consume()
                val text = msg.payload.decodeToString()
                val headEnd = text.indexOf("\r\n\r\n")
                val head = if (headEnd >= 0) text.substring(0, headEnd) else text
                val body = if (headEnd >= 0) text.substring(headEnd + 4) else ""
                val (method, rawPath) = head.lineSequence().first().split(' ').let { it[0] to it[1] }
                val reply = surface.handle(method, rawPath, body)
                val payload = reply.body.encodeToByteArray()
                val wire = buildString {
                    append("HTTP/1.1 ").append(reply.status).append(' ').append(reason(reply.status)).append("\r\n")
                    append("Server: TrikeShed/litebike (CouchDB/${CouchHttpSurface.COUCH_VERSION})\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(payload.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }.encodeToByteArray() + payload
                msg.respond?.invoke(wire)
            }
        }
        val bind = serverScope.launch {
            JvmLitebikeBindAdapter.bindAndServe(listener, port = port, host = "127.0.0.1", connections = connections)
        }
        awaitPort(port)

        val ops = JvmChannelOperations(entries = 2)
        val reactor = HtxReactorElement(channelOperations = ops).also { it.open() }
        val client = openHtxClientReactorElement(routeService = reactor)
        val base = "http://127.0.0.1:$port"

        try {
            // ── hello ────────────────────────────────────────────────
            val hello = client.json(base, "/")
            assertEquals("Welcome", hello.first["couchdb"])
            assertEquals("1.6.2", hello.first["version"])

            // ── write: PUT documents, 201 + rev ──────────────────────
            val a = client.json(base, "/$db/a", HtxMethod.PUT, """{"type":"widget","qty":2}""")
            assertEquals(201, a.second)
            assertEquals(true, a.first["ok"])
            val revA1 = a.first["rev"] as String
            assertEquals(201, client.json(base, "/$db/b", HtxMethod.PUT, """{"type":"widget","qty":5}""").second)
            assertEquals(201, client.json(base, "/$db/c", HtxMethod.PUT, """{"type":"gadget","qty":7}""").second)

            // ── doc: GET round-trips _id/_rev and body ───────────────
            val got = client.json(base, "/$db/a")
            assertEquals(200, got.second)
            assertEquals("a", got.first["_id"])
            assertEquals(revA1, got.first["_rev"])
            assertEquals("widget", got.first["type"])

            // ── write discipline: stale/missing rev → 409, right rev → new rev ──
            assertEquals(409, client.json(base, "/$db/a", HtxMethod.PUT, """{"type":"widget","qty":3}""").second)
            assertEquals(409, client.json(base, "/$db/a", HtxMethod.PUT, """{"_rev":"0-bogus","type":"widget","qty":3}""").second)
            val a2 = client.json(base, "/$db/a", HtxMethod.PUT, """{"_rev":"$revA1","type":"widget","qty":3}""")
            assertEquals(201, a2.second)
            val revA2 = a2.first["rev"] as String
            assertNotEquals(revA1, revA2)
            assertEquals(3L, client.json(base, "/$db/a").first["qty"].asLong())

            val info = client.json(base, "/$db")
            assertEquals(db, info.first["db_name"])
            assertEquals(3L, info.first["doc_count"].asLong())

            // ── design doc with views (RequestFactory view-spec shape) ──
            val ddoc = """{"views":{
                "by_type":{"map":{"key":"type","value":"qty"},"reduce":"_sum"},
                "count_by_type":{"map":{"key":"type"},"reduce":"_count"},
                "by_qty":{"map":{"key":"qty","value":"doc"}}
            }}"""
            val ddocPut = client.json(base, "/$db/_design/rf", HtxMethod.PUT, ddoc)
            assertEquals(201, ddocPut.second)
            val revD1 = ddocPut.first["rev"] as String
            val ddocGot = client.json(base, "/$db/_design/rf")
            assertEquals(200, ddocGot.second)
            assertEquals("_design/rf", ddocGot.first["_id"])
            assertEquals(revD1, ddocGot.first["_rev"])
            // GET body round-trips the views map
            val views = ddocGot.first["views"] as Map<*, *>
            assertEquals(setOf("by_type", "count_by_type", "by_qty"), views.keys.map { it.toString() }.toSet())
            assertEquals("_sum", (views["by_type"] as Map<*, *>)["reduce"])
            assertEquals("type", ((views["by_type"] as Map<*, *>)["map"] as Map<*, *>)["key"])
            assertEquals("doc", ((views["by_qty"] as Map<*, *>)["map"] as Map<*, *>)["value"])
            // design-doc write discipline: PUT without rev → 409; correct rev → 201 and a new rev
            assertEquals(409, client.json(base, "/$db/_design/rf", HtxMethod.PUT, ddoc).second)
            val ddocPut2 = client.json(base, "/$db/_design/rf?rev=$revD1", HtxMethod.PUT, ddoc)
            assertEquals(201, ddocPut2.second)
            assertNotEquals(revD1, ddocPut2.first["rev"] as String)
            assertEquals(ddocPut2.first["rev"], client.json(base, "/$db/_design/rf").first["_rev"])

            // ── view params: reduce=true&group=true ──────────────────
            val grouped = client.json(base, "/$db/_design/rf/_view/by_type?reduce=true&group=true")
            assertEquals(200, grouped.second)
            assertEquals<Map<Any?, Long>>(mapOf("gadget" to 7L, "widget" to 8L), grouped.rows().associate { it["key"] to it["value"].asLong() })

            val counted = client.json(base, "/$db/_design/rf/_view/count_by_type?group=true")
            assertEquals<Map<Any?, Long>>(mapOf("gadget" to 1L, "widget" to 2L), counted.rows().associate { it["key"] to it["value"].asLong() })

            // group=false → single rereduced row with null key (1.6.2)
            val total = client.json(base, "/$db/_design/rf/_view/by_type")
            assertEquals<Int>(1, total.rows().size)
            assertNull(total.rows()[0]["key"])
            assertEquals(15L, total.rows()[0]["value"].asLong())

            // ── view params: reduce=false&key="widget"&include_docs=true ──
            val widgets = client.json(base, "/$db/_design/rf/_view/by_type?reduce=false&key=%22widget%22&include_docs=true")
            assertEquals(200, widgets.second)
            assertEquals(3L, widgets.first["total_rows"].asLong())
            assertEquals(0L, widgets.first["offset"].asLong())
            assertEquals(listOf("a", "b"), widgets.rows().map { it["id"] })
            assertEquals(listOf("widget", "widget"), widgets.rows().map { it["key"] })
            assertEquals(listOf(3L, 5L), widgets.rows().map { (it["doc"] as Map<*, *>)["qty"].asLong() })
            assertEquals(revA2, (widgets.rows()[0]["doc"] as Map<*, *>)["_rev"])

            // ── view params: startkey/endkey (numeric collation), descending, limit, skip ──
            val mid = client.json(base, "/$db/_design/rf/_view/by_qty?startkey=4&endkey=7")
            assertEquals(listOf("b", "c"), mid.rows().map { it["id"] })
            val exclusive = client.json(base, "/$db/_design/rf/_view/by_qty?startkey=4&endkey=7&inclusive_end=false")
            assertEquals(listOf("b"), exclusive.rows().map { it["id"] })
            val desc = client.json(base, "/$db/_design/rf/_view/by_qty?descending=true&limit=2")
            assertEquals(listOf("c", "b"), desc.rows().map { it["id"] })
            assertEquals(listOf(7L, 5L), desc.rows().map { it["key"].asLong() })
            val skipped = client.json(base, "/$db/_design/rf/_view/by_qty?skip=2")
            assertEquals(2L, skipped.first["offset"].asLong())
            assertEquals(listOf("c"), skipped.rows().map { it["id"] })
            assertEquals("widget", ((desc.rows()[1]["value"]) as Map<*, *>)["type"])

            // reduce requested on a map-only view → 400 query_parse_error (1.6.2)
            assertEquals(400, client.json(base, "/$db/_design/rf/_view/by_qty?reduce=true").second)
            // missing view → 404
            assertEquals(404, client.json(base, "/$db/_design/rf/_view/nope").second)

            // ── RequestFactory envelope over POST /{db}: get + query + put in one batch ──
            val envelope = """{"operations":[
                {"op":"get","id":"c"},
                {"op":"query","view":{"name":"by_type","key":"type","value":"qty","reduce":"_sum","prefix":""}},
                {"op":"put","id":"d","doc":{"type":"gadget","qty":1}}
            ]}"""
            val rf = client.json(base, "/$db", HtxMethod.POST, envelope)
            assertEquals(200, rf.second)
            assertEquals(true, rf.first["ok"])
            val receipts = rf.first["receipts"] as List<*>
            assertEquals(3, receipts.size)
            assertEquals("gadget", ((receipts[0] as Map<*, *>)["doc"] as Map<*, *>)["type"])
            val queryReceipt = receipts[1] as Map<*, *>
            assertEquals("_design/rf/by_type", queryReceipt["view"])
            assertTrue((queryReceipt["proofCid"] as String).isNotEmpty())
            assertEquals(true, (receipts[2] as Map<*, *>)["ok"])
            // the batched put is visible to the HTTP surface
            assertEquals("gadget", client.json(base, "/$db/d").first["type"])
            // ── ViewQuery dialect: envelope startkey/endkey/descending == HTTP view for the same params ──
            val rangeHttp = client.json(base, "/$db/_design/rf/_view/by_type?reduce=false&startkey=%22widget%22&endkey=%22gadget%22&descending=true")
            assertEquals(200, rangeHttp.second)
            val rangeRf = client.json(base, "/$db", HtxMethod.POST, """{"operations":[
                {"op":"query","view":{"name":"by_type","key":"type","value":"qty","prefix":"","startkey":"widget","endkey":"gadget","descending":true}}
            ]}""")
            assertEquals(200, rangeRf.second)
            val rangeReceipt = (rangeRf.first["receipts"] as List<*>)[0] as Map<*, *>
            assertEquals(true, rangeReceipt["ok"])
            @Suppress("UNCHECKED_CAST")
            val rfRows = (rangeReceipt["rows"] as List<*>).map { it as Map<String, Any?> }
            val shape = { rows: List<Map<String, Any?>> -> rows.map { Triple(it["id"], it["key"], it["value"].asLong()) } }
            assertEquals(listOf("widget", "widget", "gadget", "gadget"), rangeHttp.rows().map { it["key"] })
            assertEquals(shape(rangeHttp.rows()), shape(rfRows))
            assertEquals(rangeHttp.first["offset"].asLong(), rangeReceipt["offset"].asLong())
            // paging goes through the same ViewQuery: skip=1&limit=2 on both sides
            val pageHttp = client.json(base, "/$db/_design/rf/_view/by_type?reduce=false&descending=true&skip=1&limit=2")
            val pageRf = client.json(base, "/$db", HtxMethod.POST, """{"operations":[
                {"op":"query","view":{"name":"by_type","key":"type","value":"qty","descending":true,"skip":1,"limit":2}}
            ]}""")
            @Suppress("UNCHECKED_CAST")
            val pageRows = ((((pageRf.first["receipts"] as List<*>)[0] as Map<*, *>)["rows"]) as List<*>).map { it as Map<String, Any?> }
            assertEquals(2, pageRows.size)
            assertEquals(shape(pageHttp.rows()), shape(pageRows))
            // CouchDB 1.6.2 counts design docs in doc_count: a, b, c, _design/rf, d = 5 live docs.
            val liveDocs = listOf("a", "b", "c", "_design/rf", "d")
            for (id in liveDocs) assertEquals(200, client.json(base, "/$db/$id").second, id)
            assertEquals(liveDocs.size.toLong(), client.json(base, "/$db").first["doc_count"].asLong())

            // bare POST /{db} → implicit put, 201
            val bare = client.json(base, "/$db", HtxMethod.POST, """{"_id":"e","type":"widget","qty":9}""")
            assertEquals(201, bare.second)
            assertEquals("e", bare.first["id"])

            // ── delete: needs rev; then 404 ──────────────────────────
            assertEquals(409, client.json(base, "/$db/a", HtxMethod.DELETE).second)
            assertEquals(200, client.json(base, "/$db/a?rev=$revA2", HtxMethod.DELETE).second)
            val gone = client.json(base, "/$db/a")
            assertEquals(404, gone.second)
            assertEquals("not_found", gone.first["error"])
            assertEquals("missing", gone.first["reason"])

            // unknown db → 404 no_db_file
            assertEquals("no_db_file", client.json(base, "/other").first["reason"])

            // ── wire proof: raw socket sees a CouchDB-shaped response line/headers ──
            val raw = Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET /$db/b HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".encodeToByteArray())
                s.getInputStream().readBytes().decodeToString()
            }
            assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"), raw)
            assertTrue("Server: TrikeShed/litebike (CouchDB/1.6.2)" in raw, raw)
            assertTrue("\"_id\":\"b\"" in raw, raw)
        } finally {
            client.close()
            reactor.close()
            ops.ioWorkers.shutdownNow()
            bind.cancelAndJoin()
            serverScope.cancel()
            listener.close()
            connections.closeAll()
        }
    }

    // ── helpers ───────────────────────────────────────────────────

    private suspend fun HtxClientReactorElement.json(
        base: String,
        path: String,
        method: HtxMethod = HtxMethod.GET,
        body: String? = null,
    ): Pair<Map<String, Any?>, Int> {
        val response: HtxResponse = withTimeout(10_000) {
            request(parseHtxRequest(base + path, method = method, body = ByteSeries(body ?: "")))
        }
        val text = response.body.asString()
        val parsed = JsonSupport.parse(text) as? Map<*, *> ?: error("non-object reply ${response.status}: $text")
        return parsed.entries.associate { it.key.toString() to it.value } to response.status
    }

    @Suppress("UNCHECKED_CAST")
    private fun Pair<Map<String, Any?>, Int>.rows(): List<Map<String, Any?>> =
        (first["rows"] as List<*>).map { it as Map<String, Any?> }

    private fun Any?.asLong(): Long = (this as Number).toLong()

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 404 -> "Object Not Found"
        405 -> "Method Not Allowed"; 409 -> "Conflict"; else -> "Internal Server Error"
    }

    private fun awaitPort(port: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            try { Socket("127.0.0.1", port).close(); return } catch (_: Exception) { Thread.sleep(20) }
        }
        error("litebike never bound :$port")
    }
}
