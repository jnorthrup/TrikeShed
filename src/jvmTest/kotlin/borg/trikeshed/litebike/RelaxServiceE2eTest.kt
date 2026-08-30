package borg.trikeshed.litebike

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.btrfs.UserspaceBtrfs
import borg.trikeshed.btrfs.VmWorldTeleport
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchReportEvent
import borg.trikeshed.couch.CouchReportReactorElement
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.job.CasStore
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
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole rxfhtx service over one real loopback socket — the smoke test for the surfaces this
 * work added, exercised as a client actually reaches them rather than as unit calls.
 *
 *   HtxClientReactorElement → real TCP → JvmLitebikeBindAdapter → LitebikeListenerElement[Http]
 *     → HTTP worker → CouchWireRouter (the daemon's own mount)
 *
 * Proves over the wire: the RequestFactory envelope at `_relax`, a stored view addressed through
 * that envelope, the confix cascade as a reduce option, the ReportServer observing both askers, and
 * a file-based btrfs VM world published onto the CAS lane and read back as an attachment.
 */
class RelaxServiceE2eTest {

    private val db = "trikeshed"

    @Test
    fun theServiceAnswersOnOneSocket() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val cas = CasStore.inMemory()
        val database = CouchDatabase(db, CouchStoreFactory.casBacked(cas), cas)
        val report = CouchReportReactorElement().also { it.open() }
        val router = CouchWireRouter(database, "projects/trikeshed/", report = report)
        val worlds = BtrfsWorldStore.ofFiles(InMemoryFileOperations(cwd = "/"), "/vm-worlds")
        val teleport = VmWorldTeleport(database, worlds)

        val listener = LitebikeListenerElement().also { it.open() }
        val httpSlot = listener.register(Protocol.Http)
        val connections = ConnectionRegistry()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        serverScope.launch {
            while (true) {
                val msg = httpSlot.consume()
                val payload = msg.payload
                val text = payload.decodeToString()
                val headEnd = text.indexOf("\r\n\r\n")
                val head = if (headEnd >= 0) text.substring(0, headEnd) else text
                val bodyBytes = if (headEnd >= 0) payload.copyOfRange(headEnd + 4, payload.size) else ByteArray(0)
                val (method, rawPath) = head.lineSequence().first().split(' ').let { it[0] to it[1] }
                val reply = router.handle(method, rawPath, bodyBytes)
                    ?: borg.trikeshed.couch.WireReply.notFound("no route")
                val wire = buildString {
                    append("HTTP/1.1 ").append(reply.status).append(" OK\r\n")
                    append("Content-Type: ").append(reply.contentType).append("\r\n")
                    append("Content-Length: ").append(reply.bytes.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }.encodeToByteArray() + reply.bytes
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
            // ── the RequestFactory envelope, over the wire, at its own endpoint ──
            val seeded = client.json(
                base, "/$db/_relax", HtxMethod.POST,
                """{"operations":[
                    {"op":"put","id":"m1","doc":{"machine_id":"m1","cpu_mhz":100,"memory_mib":512,"reading_date":"2026-08-30T00:00:00Z"}},
                    {"op":"put","id":"m2","doc":{"machine_id":"m1","cpu_mhz":300,"memory_mib":1536,"reading_date":"2026-08-30T00:01:00Z"}},
                    {"op":"put","id":"m3","doc":{"machine_id":"m2","cpu_mhz":50,"memory_mib":256,"reading_date":"2026-08-30T00:02:00Z"}}
                ]}""",
            )
            assertEquals(200, seeded.second)
            assertEquals(true, seeded.first["ok"], "envelope batch failed: ${seeded.first}")
            @Suppress("UNCHECKED_CAST")
            val receipts = (seeded.first["receipts"] as List<*>).map { it as Map<String, Any?> }
            assertEquals(3, receipts.size)
            // Every revision is CAS-derived, which is what makes these documents replicable.
            for (r in receipts) assertNotNull(CouchDatabase.revToCid(r["rev"] as String), "rev ${r["rev"]} names no blob")

            // it entered _changes, over the wire
            val changes = client.json(base, "/$db/_changes?since=0")
            assertEquals(3, (changes.first["results"] as List<*>).size)

            // ── a design doc naming the confix cascade as its reducer ──
            assertEquals(
                201,
                client.json(
                    base, "/$db/_design/metrics", HtxMethod.PUT,
                    """{"views":{"byMachine":{"map":{"key":"machine_id","value":"doc"},"reduce":"_cascade"}}}""",
                ).second,
            )

            // the route serves it
            val viaRoute = client.json(base, "/$db/_design/metrics/_view/byMachine?group=true")
            assertEquals(200, viaRoute.second)
            val routeRows = viaRoute.rows()
            assertEquals(2, routeRows.size, "one row per machine")
            val m1Route = routeRows.first { it["key"] == "m1" }
            assertEquals(400.0, cascadeMetric(m1Route["value"], "cpu_mhz", "sum"))

            // and the envelope addresses the SAME stored view — the asymmetry this work closed
            val viaEnvelope = client.json(
                base, "/$db/_relax", HtxMethod.POST,
                """{"operations":[{"op":"view","ddoc":"_design/metrics","name":"byMachine","params":{"group":true}}]}""",
            )
            assertEquals(200, viaEnvelope.second)
            @Suppress("UNCHECKED_CAST")
            val envReceipt = (viaEnvelope.first["receipts"] as List<*>)[0] as Map<String, Any?>
            assertEquals(true, envReceipt["ok"], "stored view over the wire failed: $envReceipt")
            @Suppress("UNCHECKED_CAST")
            val envRows = (envReceipt["rows"] as List<*>).map { it as Map<String, Any?> }
            val m1Env = envRows.first { it["key"] == "m1" }
            assertEquals(
                cascadeMetric(m1Route["value"], "cpu_mhz", "sum"),
                cascadeMetric(m1Env["value"], "cpu_mhz", "sum"),
                "the route and the envelope disagree on one stored cascade view",
            )

            // ── the ReportServer saw both askers ──
            val state = report.reportState.value
            assertTrue(state.mapEmissions > 0, "no map emissions reached the report bus")
            assertTrue(state.reductions >= 2, "both askers should have reduced; got ${state.reductions}")
            assertTrue(
                report.events.replayCache.any { it is CouchReportEvent.MapEmitted },
                "the report bus carries no MapEmitted facts",
            )

            // ── the project heading, over the wire ──
            val declared = client.json(
                base, "/$db/_relax", HtxMethod.POST,
                """{"operations":[
                    {"op":"project_put","id":"trikeshed","doc":{"head":"dcf437f3"}},
                    {"op":"put","id":"projects/trikeshed/docs/index.html","doc":{"contentType":"text/html"}},
                    {"op":"put","id":"projects/trikeshed/src/X.kt","doc":{"contentType":"text/x-kotlin"}}
                ]}""",
            )
            assertEquals(200, declared.second)
            assertEquals(true, declared.first["ok"], "declaring the heading failed: ${declared.first}")

            // the heading knows what hangs under it, through the envelope...
            val underHeading = client.json(
                base, "/$db/_relax", HtxMethod.POST,
                """{"operations":[{"op":"project_docs","id":"trikeshed","under":"src/"}]}""",
            )
            @Suppress("UNCHECKED_CAST")
            val headingReceipt = (underHeading.first["receipts"] as List<*>)[0] as Map<String, Any?>
            assertEquals(true, headingReceipt["ok"], "project_docs failed: $headingReceipt")
            @Suppress("UNCHECKED_CAST")
            val headingRows = (headingReceipt["rows"] as List<*>).map { it as Map<String, Any?> }
            assertEquals(listOf("src/X.kt"), headingRows.map { it["path"] })

            // ...and the route agrees about which headings exist
            val projectRoute = client.json(base, "/$db/_projects")
            assertEquals(200, projectRoute.second)
            @Suppress("UNCHECKED_CAST")
            val projectRows = (projectRoute.first["rows"] as List<*>).map { it as Map<String, Any?> }
            val trikeshed = projectRows.first { it["id"] == "trikeshed" }
            assertEquals(true, trikeshed["declared"])
            assertEquals("dcf437f3", trikeshed["head"])

            // ── a file-based btrfs VM world, published onto the CAS lane ──
            val fs = UserspaceBtrfs(worlds.root, worlds.fileOpsFor("vm.e2e"))
            check(fs.createSubvolume(worlds.subvolumeFor("vm.e2e")))
            check(fs.writeFile("vm.e2e", "workspace/model.txt", "trained".encodeToByteArray()))
            val published = teleport.publish("vm.e2e")
            assertEquals(true, published["ok"], "publish failed: $published")
            val cid = published["cid"] as String

            // the world is an ordinary attachment document on the wire
            val worldDoc = client.json(base, "/$db/vm-worlds/vm.e2e")
            assertEquals(200, worldDoc.second)
            assertEquals("vm-world", worldDoc.first["kind"])
            val stub = (worldDoc.first["_attachments"] as Map<*, *>)["content"] as Map<*, *>
            assertEquals(VmWorldTeleport.CONTENT_TYPE, stub["content_type"])

            // and its bytes come back off the CAS lane and the IPFS alias, byte-identical
            val fromCas = bytes(base, "/$db/_cas/$cid", port)
            val fromIpfs = bytes(base, "/api/v0/block/get?arg=$cid", port)
            val fromAttachment = bytes(base, "/$db/vm-worlds/vm.e2e/content", port)
            assertContentEquals(fromCas, fromIpfs, "the CAS lane and the IPFS alias disagree")
            assertContentEquals(fromCas, fromAttachment, "the attachment route serves different bytes")

            // the stream that crossed really does reconstitute a world
            assertTrue(fs.receive("vm.e2e.restored", fromCas), "the replicated stream did not receive")
            assertEquals("trained", fs.fetchFile("vm.e2e.restored", "workspace/model.txt")?.decodeToString())
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

    @Suppress("UNCHECKED_CAST")
    private fun cascadeMetric(value: Any?, field: String, stat: String): Double {
        val rollup = (value as List<*>)[0] as Map<*, *>
        return ((rollup[field] as Map<*, *>)[stat] as Number).toDouble()
    }

    /** Raw bytes for the binary lanes, straight off a socket — JSON parsing would corrupt them. */
    private fun bytes(base: String, path: String, port: Int): ByteArray =
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".encodeToByteArray())
            val all = s.getInputStream().readBytes()
            val sep = "\r\n\r\n".encodeToByteArray()
            var i = 0
            outer@ while (i <= all.size - sep.size) {
                for (k in sep.indices) if (all[i + k] != sep[k]) { i++; continue@outer }
                return all.copyOfRange(i + sep.size, all.size)
            }
            error("no header terminator in reply to $path")
        }

    private suspend fun HtxClientReactorElement.json(
        base: String,
        path: String,
        method: HtxMethod = HtxMethod.GET,
        body: String? = null,
    ): Pair<Map<String, Any?>, Int> {
        val response: HtxResponse = withTimeout(15_000) {
            request(parseHtxRequest(base + path, method = method, body = ByteSeries(body ?: "")))
        }
        val text = response.body.asString()
        val parsed = JsonSupport.parse(text) as? Map<*, *> ?: error("non-object reply ${response.status}: $text")
        return parsed.entries.associate { it.key.toString() to it.value } to response.status
    }

    @Suppress("UNCHECKED_CAST")
    private fun Pair<Map<String, Any?>, Int>.rows(): List<Map<String, Any?>> =
        (first["rows"] as List<*>).map { it as Map<String, Any?> }

    private fun awaitPort(port: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            try { Socket("127.0.0.1", port).close(); return } catch (_: Exception) { Thread.sleep(20) }
        }
        error("litebike never bound :$port")
    }
}
