package borg.trikeshed.forge.server

import borg.trikeshed.job.CasStore
import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.couch.replicate.HttpExchange
import borg.trikeshed.couch.replicate.HttpReply
import borg.trikeshed.couch.revWins
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.htx.emptyHtxBody
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.openHtxClientReactorElement
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two daemons' worth of couch surface on two real loopback sockets, replicating through the
 * SAME client path the daemon uses for `_replicate` — an HTX client element, not the router
 * called in-process. This is the checked-in form of the 2026-08-29 / 2026-09-05 two-daemon
 * experiments (ports 8891/8892, then 8930/8934), which in-process tests could never reproduce:
 *
 *  - a document and the blob it references land on the peer by pull, and another by push;
 *  - two heads for one id resolve by the 1.x winner rule and are REPORTED as conflicts — the
 *    pass completes and the checkpoint advances (the 2026-09-05 defect was a 502 forever);
 *  - the IPFS alias `/api/v0/block/get` answers on the socket with the same bytes as `_cas`
 *    (the 2026-09-05 defect: `CouchWire` owned only `/{db}` paths, so the alias was 404 on the
 *    daemon while every in-process test passed).
 */
class CouchWireSocketTest {

    private class Node(val name: String, exchange: HttpExchange, val scope: CoroutineScope) {
        val port = ServerSocket(0).use { it.localPort }
        val cas = CasStore.inMemory()
        val db = Couch("trikeshed", CouchStoreFactory.casBacked(cas), cas)
        val replicator = CouchReplicator(db, exchange)
        val router = CouchWireRouter(db, "projects/trikeshed/", replicator = replicator)
        val wire = CouchWire(router, replicator, scope)
        val base = "http://127.0.0.1:$port/trikeshed"
        fun start() {
            val server = JvmKanbanServer(rawRoutes = listOf(wire::route), stateDir = Files.createTempDirectory("couch-socket-$name-").toFile())
            scope.launch { server.run(port, null) }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                if (runCatching { http(port, "GET", "/trikeshed/_changes?limit=1").first }.getOrNull() == 200) return
                Thread.sleep(50)
            }
            error("node $name never answered on :$port")
        }
    }

    @Test
    fun twoNodesReplicateOverRealSocketsThroughTheHtxClient() = runBlocking {
        // Server coroutines die by cancellation at the end; a handler keeps their last gasps from
        // reaching the thread's uncaught handler, where kotlinx-test would blame the NEXT suite.
        val serverJob = SupervisorJob()
        val scope = CoroutineScope(serverJob + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
        val ops = JvmChannelOperations(entries = 2)
        val reactor = HtxReactorElement(channelOperations = ops).also { it.open() }
        val client = openHtxClientReactorElement(routeService = reactor)
        // The daemon's peer exchange, verbatim in shape: parseHtxRequest → element.request → HttpReply.
        val exchange = HttpExchange { method, url, body, contentType ->
            val req = parseHtxRequest(url, method = HtxMethod.valueOf(method.uppercase()),
                body = body?.let { ByteSeries(it) } ?: emptyHtxBody())
                .let { r -> if (contentType != null) r.copy(headers = htxHeaders("Content-Type" j contentType)) else r }
            val resp = client.request(req)
            HttpReply(resp.status, resp.body.toArray())
        }
        val a = Node("a", exchange, scope)
        val b = Node("b", exchange, scope)
        try {
            a.start(); b.start()

            // ── a blob and the document that references it, on A over the socket ──
            val payload = "m2m probe blob, over the wire".encodeToByteArray()
            val cid = ContentId.of(payload).value
            val blobPut = http(a.port, "POST", "/trikeshed/_cas", payload, "application/octet-stream")
            assertTrue(blobPut.first in 200..299, "blob put on A: ${blobPut.first} ${blobPut.second.decodeToString()}")
            val put = http(a.port, "PUT", "/trikeshed/m2m-doc-1",
                """{"kind":"m2m-probe","contentId":"$cid","note":"pull me"}""".encodeToByteArray(), "application/json")
            assertEquals(201, put.first, "doc put on A: ${put.second.decodeToString()}")
            assertEquals(404, http(b.port, "GET", "/trikeshed/m2m-doc-1").first, "B does not have it yet")

            // ── B pulls A through the HTX client ──
            val pull = json(http(b.port, "POST", "/trikeshed/_replicate",
                """{"source":"${a.base}","target":"trikeshed"}""".encodeToByteArray(), "application/json"))
            assertEquals(true, pull["ok"], "pull failed: $pull")
            assertEquals(1, (pull["docs_written"] as Number).toInt(), "pull: $pull")
            assertEquals(0, (pull["conflicts"] as Number).toInt())
            assertTrue((pull["blobs_transferred"] as Number).toInt() >= 2, "body blob + referenced blob: $pull")
            assertEquals(200, http(b.port, "GET", "/trikeshed/m2m-doc-1").first, "the document landed on B")
            assertContentEquals(payload, bytes(b.port, "/trikeshed/_cas/$cid"), "the referenced blob landed on B")
            assertContentEquals(payload, bytes(b.port, "/api/v0/block/get?arg=$cid"), "the IPFS alias answers on the socket")
            val bad = http(b.port, "GET", "/api/v0/block/get?arg=bafkreigh2akiscaildcqabsyg3dfr6chu3fgpregiymsck7e7aqa4s52zy")
            assertEquals(404, bad.first, "a CIDv1 key is not a ContentId yet: ${bad.second.decodeToString()}")

            // ── two heads for one id: the winner rule decides, the pass still completes ──
            http(a.port, "PUT", "/trikeshed/shared", """{"v":"from-a"}""".encodeToByteArray(), "application/json")
            http(b.port, "PUT", "/trikeshed/shared", """{"v":"from-b"}""".encodeToByteArray(), "application/json")
            val revA = a.db.store.head.getRev("shared")!!; val revB = b.db.store.head.getRev("shared")!!
            check(revA != revB) { "two bodies must mint two revisions" }
            val second = json(http(b.port, "POST", "/trikeshed/_replicate",
                """{"source":"${a.base}","target":"trikeshed"}""".encodeToByteArray(), "application/json"))
            assertEquals(true, second["ok"], "a declined loser must not fail the pass: $second")
            val aWins = revWins(revA, revB)
            assertEquals(if (aWins) 1 else 0, (second["docs_written"] as Number).toInt(), "winner landed: $second")
            assertEquals(if (aWins) 0 else 1, (second["conflicts"] as Number).toInt(), "loser reported: $second")
            assertEquals(if (aWins) revA else revB, b.db.store.head.getRev("shared"), "B holds the 1.x winner")
            val again = json(http(b.port, "POST", "/trikeshed/_replicate",
                """{"source":"${a.base}","target":"trikeshed"}""".encodeToByteArray(), "application/json"))
            assertEquals(0, (again["docs_read"] as Number).toInt(), "the checkpoint advanced past the conflict: $again")

            // ── A pushes to B ──
            http(a.port, "PUT", "/trikeshed/m2m-doc-2", """{"kind":"m2m-probe","note":"push me"}""".encodeToByteArray(), "application/json")
            val push = json(http(a.port, "POST", "/trikeshed/_replicate",
                """{"source":"trikeshed","target":"${b.base}"}""".encodeToByteArray(), "application/json"))
            assertEquals(true, push["ok"], "push failed: $push")
            assertTrue((push["docs_written"] as Number).toInt() >= 1, "push: $push")
            assertEquals(200, http(b.port, "GET", "/trikeshed/m2m-doc-2").first, "the pushed document landed on B")
        } finally {
            client.close()
            reactor.close()
            ops.ioWorkers.shutdownNow()
            serverJob.cancelAndJoin()
        }
    }

    // ── plain-socket helpers: the client side of the experiment, without HTX in the way ──

    private companion object {
        fun http(port: Int, method: String, path: String, body: ByteArray? = null, contentType: String? = null): Pair<Int, ByteArray> {
            val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000; conn.readTimeout = 60_000
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            return try {
                val status = conn.responseCode
                val bytes = (if (status < 400) conn.inputStream else conn.errorStream)?.readBytes() ?: ByteArray(0)
                status to bytes
            } finally { conn.disconnect() }
        }

        @Suppress("UNCHECKED_CAST")
        fun json(reply: Pair<Int, ByteArray>): Map<String, Any?> =
            (JsonSupport.parse(reply.second.decodeToString()) as? Map<String, Any?>) ?: error("non-object ${reply.first}: ${reply.second.decodeToString()}")

        /** Raw bytes straight off a socket — a JSON-minded client would corrupt a blob. */
        fun bytes(port: Int, path: String): ByteArray = Socket("127.0.0.1", port).use { s ->
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
    }
}
