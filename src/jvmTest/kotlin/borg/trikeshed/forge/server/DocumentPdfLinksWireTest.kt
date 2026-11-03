package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.*

class DocumentPdfLinksWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private fun pdf(url: String): ByteArray {
        val objects = s_["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Annots [4 0 R 5 0 R] >>",
            "<< /Type /Annot /Subtype /Link /Rect [10 10 100 30] /A << /S /URI /URI ($url) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [10 40 100 60] /A << /S /URI /URI (javascript:bad) >> >>"]
        val output = ByteArrayOutputStream(); val offsets = mutableListOf(0)
        fun write(value: String) { output.write(value.encodeToByteArray()) }
        write("%PDF-1.7\n")
        for (i in 0 until objects.size) { offsets.add(output.size()); write("${i + 1} 0 obj\n${objects[i]}\nendobj\n") }
        val start = output.size(); write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets.drop(1)) write("%010d 00000 n \n".format(offset))
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
        return output.toByteArray()
    }
    @Test fun reviewLifecyclePublishesDiscoveryWithoutReviewerOrModel(): Unit = runBlocking {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve("lifecycle-cas").toString())
        val owner = SupervisorJob(coroutineContext[Job]); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("pdf-links-lifecycle", store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val books = borg.trikeshed.narsese.ConstellationNodes(directory.toFile(), ctx.blackboard, scope = scope, cas = cas)
        val review = DocumentReviewWire.open(ctx, InMemoryProjectCorpus(), books)
        try {
            val route = assertNotNull(ctx.routes.match("/api/documents/pdf-links"))
            assertEquals(405, assertNotNull(route.route("GET", "/api/documents/pdf-links", "", null)).status)
            assertNotNull(ctx.routes.match("/api/documents/pdf-links/collect"))
        } finally { review.drain(); review.close(); books.drain(); owner.complete(); owner.join() }
        assertNull(ctx.routes.match("/api/documents/pdf-links"))
        assertNull(ctx.routes.match("/api/documents/pdf-links/collect"))
    }

    @Test fun retainedPdfDiscoversAndCollectsOnlySelectedLinkWithoutAutomaticIntake(): Unit = runBlocking {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve("cas").toString())
        val owner = SupervisorJob(coroutineContext[Job]); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas); val gateway = CouchAttachmentGateway(store, cas)
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(files, gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))), cas,
            null, projectDbs = registry, ledgerFile = directory.resolve("projects.tsv").toFile(), filesRoot = directory.resolve("files").toFile())
        val corpus = JvmProjectCorpus(registry, scopes)
        val reactor = HtxReactorElement(JvmChannelOperations(), parentJob = owner)
        withContext(scope.coroutineContext) { reactor.open() }
        val htx = openHtxElement(routeService = reactor, parentJob = owner)
        val ctx = ModuleContext(Couch("pdf-links", store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, gateway, ModuleRouteRegistry(), scope, { 123L }, directory.toFile(), htx)
        val wire = DocumentPdfLinksWire(ctx, corpus)
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val page = "<html><body>retained linked page</body></html>".encodeToByteArray()
        var automatic = 0; scopes.onDocument = { _, _, _ -> automatic++ }
        val serving = scope.launch {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(); while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".encodeToByteArray() + page)
            }
        }
        suspend fun request(suffix: String, input: Map<String, Any?>, status: Int = 200): Map<String, Any?> {
            val path = "/api/documents/pdf-links$suffix"
            val response = assertNotNull(assertNotNull(ctx.routes.match(path)).route("POST", path, jsonOf(input), null))
            assertEquals(status, response.status, response.body); return reifyMap(response.body)
        }
        try {
            wire.attach(); assertEquals(405, wire.route("GET", "/api/documents/pdf-links", "", null).status)
            scopes.beginUpload("pdfs", manualIntake = true)
            val bytes = pdf("http://127.0.0.1:${server.localPort}/article")
            scopes.uploadPut("pdfs", "source.pdf", bytes, null, manualIntake = true)
            val selected = assertNotNull(corpus.document("pdfs", "source.pdf"))
            val input: Map<String, Any?> = _m["project" j selected.project, "id" j selected.id, "cid" j selected.cid, "rev" j selected.rev, "seq" j selected.seq]
            val inventory = request("", input)
            assertEquals("AVAILABLE", inventory["status"]); assertEquals("unreviewed", inventory["reviewStatus"])
            val receipt = inventory["receiptCid"] as String
            val restartedWire = DocumentPdfLinksWire(ctx, corpus)
            try {
                assertEquals(409, restartedWire.route("POST", "/api/documents/pdf-links/collect",
                    jsonOf(_m["inventoryCid" j receipt, "selectionIds" j _l["link-0"]]), null).status)
            } finally { restartedWire.drain(); wire.attach() }
            assertEquals(ContentId(receipt), ContentId.of(assertNotNull(cas.get(ContentId(receipt)))))
            val links = inventory["links"] as List<*>; assertEquals(2, links.size)
            val link = links[0] as Map<*, *>; assertEquals(1, (link["page"] as Number).toInt())
            request("/collect", _m["inventoryCid" j receipt, "selectionIds" j _l[(links[1] as Map<*, *>)["id"]]], 409)
            val collection = request("/collect", _m["inventoryCid" j receipt, "selectionIds" j _l[link["id"]]])
            assertEquals("collected", collection["status"])
            val imported = (collection["documents"] as List<*>).single() as Map<*, *>
            assertContentEquals(page, corpus.bytes("pdfs", imported["documentId"] as String))
            assertEquals(ContentId.of(page).value, imported["cid"])
            assertEquals(0, automatic)
            val evidence = CanonicalCbor.decodeMap(assertNotNull(cas.get(ContentId(imported["evidenceCid"] as String))))
            assertEquals(receipt, evidence["inventoryCid"]); assertEquals("unreviewed", evidence["reviewStatus"])
            serving.join()
            request("/collect", _m["inventoryCid" j ContentId.of("forged".encodeToByteArray()).value, "selectionIds" j _l["link-0"]], 409)
            request("", input + _m["rev" j "stale"], 409)
            scopes.uploadPut("pdfs", "source.pdf", pdf("https://example.invalid/changed"), null, manualIntake = true)
            request("/collect", _m["inventoryCid" j receipt, "selectionIds" j _l[link["id"]]], 409)
            val changed = assertNotNull(corpus.document("pdfs", "source.pdf"))
            val deniedInventory = request("", _m["project" j changed.project, "id" j changed.id, "cid" j changed.cid, "rev" j changed.rev, "seq" j changed.seq])
            val denied = request("/collect", _m["inventoryCid" j deniedInventory["receiptCid"], "selectionIds" j _l["link-0"]])
            assertEquals("partial", denied["status"])
            val refusal = (denied["documents"] as List<*>).single() as Map<*, *>
            assertEquals("failed", refusal["status"])
            assertTrue((refusal["error"] as String).contains("egress"))
            assertFalse(borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist.permits("example.invalid"))
            assertEquals(0, automatic)
        } finally {
            server.close(); serving.cancelAndJoin(); wire.drain(); scopes.drain(); htx.close(); reactor.drain(); owner.complete(); owner.join()
        }
    }
}
