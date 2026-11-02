package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** Explicit local Chromium proof. Only canvas bootstrap is synthetic; review routes are the real wire. */
class StrictBookReviewLoopbackTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path

    @Test
    @org.junit.jupiter.api.Timeout(value = 3, unit = TimeUnit.MINUTES)
    fun browserUsesRealStrictBookReviewWire() = runBlocking {
        assumeTrue(System.getenv("TRIKESHED_STRICT_BOOK_BROWSER") == "1", "Opt-in browser proof")
        val bundle = Path.of(requireNotNull(System.getenv("SPACEGRAPH_BUNDLE")) { "Set SPACEGRAPH_BUNDLE to the built bundle" })
        require(Files.isRegularFile(bundle)) { "Spacegraph bundle missing" }
        val web = Path.of("src/commonMain/resources/web").toAbsolutePath()
        val script = Path.of("src/jvmTest/resources/strict-book-review-loopback.cjs").toAbsolutePath()
        val files = JvmFileOperations()
        val cas = FileCasStore(files, root.resolve("cas").toString())
        assertTrue(cas is BoundedCasReader)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("strict-book-proof", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { System.currentTimeMillis() }, root.toFile())
        val token = "synthetic-loopback-reviewer-only"
        val documents = root.resolve("documents")
        Files.createDirectories(documents)
        Files.writeString(documents.resolve("review-principals.json"), jsonOf(mapOf("principals" to listOf(mapOf(
            "tokenSha256" to ContentId.of(token.encodeToByteArray()).hex,
            "id" to "synthetic:loopback-reviewer", "synthetic" to true)))))
        val text = "Synthetic clinic provides care if funding arrives. 😀"
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "proof", "text/plain", "synthetic-loopback")
        val ontology = CorpusOntology.retain(cas, "proof", "v1", "(subclass Provider Participant)",
            mapOf("clinic" to listOf("Provider")))
        val clause = NormClause("clinic", "may", true, "provide", "care", null, text, null)
        val receipt = BookCurationReceipt.retain(cas, "proof", source, ontology,
            s_[BookClause(clause, 0, text.length, 0)], "synthetic-loopback")
        val proposal = ((BookCurationReceipt.read(cas, receipt)["relationships"] as List<*>).single() as Map<*, *>)["id"] as String
        val bookRoot = root.resolve("constellations/books")
        Files.createDirectories(bookRoot)
        Files.writeString(bookRoot.resolve("proof.source"), textCid.value)
        Files.writeString(bookRoot.resolve("proof.curation"), receipt.value)
        val books = ConstellationNodes(root.toFile(), ctx.blackboard, scope = scope, cas = cas)
        val wire = DocumentReviewWire.open(ctx, InMemoryProjectCorpus(), books)
        val snapshot = wire.review.snapshot("book", receipt, listOf(proposal))
        assertEquals(true, snapshot["current"])
        val program = mapOf("name" to "strict-loopback", "nodes" to listOf(mapOf("id" to "book", "type" to "book.snapshot",
            "x" to 350, "y" to 100, "params" to emptyMap<String, String>())), "wires" to emptyList<Any>(),
            "controls" to mapOf("inspectionOnly" to false), "view" to mapOf("x" to 0, "y" to 0, "z" to 1))
        val contracts = listOf(mapOf("type" to "book.snapshot", "title" to "Book review",
            "inputs" to emptyList<Any>(), "outputs" to emptyList<Any>()))
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
        val executor = Executors.newFixedThreadPool(4)
        server.executor = executor
        try {
            server.createContext("/") { exchange ->
                try {
                    val path = exchange.requestURI.path
                    var status = 200
                    var mime = "application/json; charset=utf-8"
                    val bytes = when {
                        path == "/_proof/bootstrap" -> jsonOf(mapOf("token" to token, "text" to text,
                            "textCid" to textCid.value, "selectionId" to proposal, "snapshotCid" to snapshot["snapshotCid"],
                            "revision" to snapshot["revision"])).encodeToByteArray()
                        path == "/api/lcnc/contracts" -> jsonOf(mapOf("contracts" to contracts)).encodeToByteArray()
                        path == "/api/panels/presets" -> jsonOf(mapOf("presets" to listOf(mapOf("name" to "strict-loopback", "document" to program)))).encodeToByteArray()
                        path == "/api/lcnc/run" -> jsonOf(mapOf("receiptCid" to receipt.value, "runId" to "synthetic-canvas-run",
                            "programCid" to receipt.value, "ok" to true, "status" to "completed", "rootStateCid" to receipt.value,
                            "outputs" to mapOf("book" to snapshot))).encodeToByteArray()
                        path == "/api/panels" -> "{\"panels\":[]}".encodeToByteArray()
                        path == "/blackboard/facts" -> { mime = "text/event-stream"; ByteArray(0) }
                        path == "/panels" -> { mime = "text/html"; Files.readAllBytes(web.resolve("panels.html")) }
                        path == "/kotlin/spacegraph/spacegraph.js" -> { mime = "application/javascript"; Files.readAllBytes(bundle) }
                        path.endsWith(".css") && !path.substringAfterLast('/').contains("..") -> {
                            mime = "text/css"; Files.readAllBytes(web.resolve(path.substringAfterLast('/')))
                        }
                        path.startsWith("/api/documents/review") -> {
                            val request = buildString {
                                append(exchange.requestMethod).append(' ').append(exchange.requestURI).append(" HTTP/1.1\r\n")
                                for (header in exchange.requestHeaders["Authorization"].orEmpty())
                                    append("Authorization: ").append(header).append("\r\n")
                                append("\r\n").append(exchange.requestBody.readBytes().decodeToString())
                            }
                            val response = runBlocking { ctx.routes.match(path)?.route(exchange.requestMethod,
                                exchange.requestURI.toString(), request, null) }
                            status = response?.status ?: 404
                            (response?.body ?: "{}").encodeToByteArray()
                        }
                        else -> { status = 404; "{}".encodeToByteArray() }
                    }
                    exchange.responseHeaders.set("Content-Type", mime)
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (failure: Exception) {
                    val bytes = jsonOf(mapOf("error" to failure.toString())).encodeToByteArray()
                    exchange.sendResponseHeaders(500, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally { exchange.close() }
            }
            server.start()
            val output = root.resolve("browser-proof.log").toFile()
            val process = ProcessBuilder(System.getenv("TRIKESHED_BROWSER_NODE") ?: "node", script.toString())
                .redirectErrorStream(true).redirectOutput(output).also {
                    it.environment()["STRICT_REVIEW_BASE"] = "http://127.0.0.1:${server.address.port}"
                }.start()
            val finished = withContext(Dispatchers.IO) { process.waitFor(120, TimeUnit.SECONDS) }
            if (!finished) process.destroyForcibly()
            assertTrue(finished, "Browser proof timed out: ${output.readText()}")
            assertEquals(0, process.exitValue(), output.readText())
            assertContains(output.readText(), "STRICT_BOOK_LOOPBACK_PASS")
            println(output.readText())
        } finally {
            server.stop(0)
            executor.shutdownNow()
            wire.drain(); wire.close(); books.drain(); owner.cancelAndJoin()
        }
    }
}
