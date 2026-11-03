package borg.trikeshed.forge.server

import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Source inspection and explicitly selected collection, never review or model authority. */
class DocumentPdfLinksWire(private val ctx: ModuleContext, private val corpus: ProjectCorpus,
    private val parser: PdfLinks = PdfLinks()) {
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext.minusKey(Job) + fileIoContext))
    // A public CAS object is data, not proof that this host issued an inventory. Restart requires rediscovery.
    private val issued = ConcurrentHashMap<String, ProjectDoc>()
    fun attach() {
        ctx.routes.claim("document-pdf-links", "/api/documents/pdf-links", route = ::route)
        ctx.routes.claim("document-pdf-links", "/api/documents/pdf-links/collect", route = ::route)
    }
    suspend fun drain() { ctx.routes.release("document-pdf-links"); requests.drain(); issued.clear() }
    private fun json(value: Any?, status: Int = 200) = WireHttpResponse(status, jsonOf(value), "application/json; charset=utf-8")
    private fun fields(input: Map<*, *>, keys: Set<String>) = require(input.keys == keys) { "Unexpected or missing fields" }
    private suspend fun current(expected: ProjectDoc) {
        val actual = corpus.document(expected.project, expected.id)
        require(actual != null && actual.cid == expected.cid && actual.rev == expected.rev && actual.seq == expected.seq) { "Selected PDF revision changed" }
    }
    private fun selected(input: Map<*, *>): ProjectDoc {
        fields(input, _s["project", "id", "cid", "rev", "seq"])
        fun text(key: String) = (input[key] as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: error("$key required")
        return ProjectDoc(text("project"), text("id"), text("cid"), text("rev"), DocumentReviewWire.revision(input["seq"]), 0, "application/pdf")
    }
    internal suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        if (method != "POST") return json(_m["error" j "method_not_allowed"], 405)
        return try { requests.call {
            val input = DocumentReviewWire.taxonomyRequest(text.substringAfter("\r\n\r\n", text))
            if (path.substringBefore('?').endsWith("/collect")) collect(input) else discover(input)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { json(_m["error" j (failure.message ?: "PDF link request refused")], 409) }
    }
    private suspend fun discover(input: Map<*, *>): WireHttpResponse {
        val expected = selected(input)
        current(expected)
        val row = requireNotNull(corpus.document(expected.project, expected.id))
        require(row.contentType.substringBefore(';') == "application/pdf" || row.id.endsWith(".pdf", true)) { "Selected source is not a PDF" }
        require(row.length in 0..MAX_PDF.toLong()) { "PDF exceeds 32MiB" }
        val reader = ctx.casStore as? BoundedCasReader ?: throw UnsupportedOperationException("Bounded original CAS reads unavailable")
        val bytes = requireNotNull(reader.getBounded(ContentId(expected.cid), MAX_PDF)) { "Retained PDF bytes unavailable" }
        require(ContentId.of(bytes).value == expected.cid && bytes.size.toLong() == row.length) { "PDF identity mismatch" }
        val inventory = parser.discover(bytes, ContentId(expected.cid))
        current(expected)
        val links = (inventory.links α { link -> _m[
            "id" j "link-${link.index}", "page" j link.page, "type" j link.type, "uri" j link.uri,
            "followable" j link.followable, "reason" j link.reason] }).toList()
        val receipt = _m["schema" j "trikeshed.pdf-links.v1", "reviewStatus" j "unreviewed",
            "selection" j _m["project" j expected.project, "id" j expected.id, "cid" j expected.cid, "rev" j expected.rev, "seq" j expected.seq],
            "sourceCid" j expected.cid, "status" j inventory.status.name, "pages" j inventory.pages,
            "parser" j inventory.parser, "toolVersion" j inventory.toolVersion, "coverage" j inventory.coverage,
            "diagnostic" j inventory.diagnostic, "links" j links, "authority" j "source-link-inventory-only"]
        val encoded = CanonicalCbor.encodeMap(receipt)
        require(encoded.size <= MAX_RECEIPT) { "Inventory receipt exceeds 2MiB" }
        val cid = ctx.casStore.put(encoded); ctx.casStore.sync()
        current(expected)
        if (inventory.status == PdfLinkStatus.AVAILABLE) {
            synchronized(issued) {
                require(issued.size < 1024 || issued.containsKey(cid.value)) { "Inventory issuance capacity; restart or reuse existing inventory" }
                issued[cid.value] = expected
            }
        }
        return json(receipt + _m["receiptCid" j cid.value, "collection" j "explicit-selection-only", "issuance" j "current-host-lifetime"])
    }
    private suspend fun collect(input: Map<*, *>): WireHttpResponse {
        fields(input, _s["inventoryCid", "selectionIds"])
        val inventoryCid = ContentId(input["inventoryCid"] as? String ?: error("inventoryCid required"))
        val expected = requireNotNull(issued[inventoryCid.value]) { "Inventory was not issued by this host lifetime; rediscover PDF links" }
        val ids = input["selectionIds"] as? List<*> ?: error("selectionIds array required")
        require(ids.size in 1..8 && ids.all { it is String } && ids.toSet().size == ids.size) { "Select 1..8 distinct link IDs" }
        val target = corpus as? JvmProjectCorpus ?: throw UnsupportedOperationException("Manual retained import unavailable")
        target.requireManualLinkProject(expected)
        current(expected)
        val reader = ctx.casStore as? BoundedCasReader ?: error("Bounded receipt read unavailable")
        val raw = requireNotNull(reader.getBounded(inventoryCid, MAX_RECEIPT)) { "Inventory receipt missing" }
        require(ContentId.of(raw) == inventoryCid) { "Inventory identity mismatch" }
        val receipt = CanonicalCbor.decodeMapBounded(raw, MAX_RECEIPT, 16, 100000)
        val links = receipt["links"] as? List<*> ?: error("Inventory links missing")
        val chosen = ids.map { id -> links.map { it as Map<*, *> }.singleOrNull { it["id"] == id } ?: error("Unknown link ID") }
        require(chosen.all { it["followable"] == true }) { "Selected link is not HTTP(S) followable" }
        val outcomes = mutableListOf<Map<String, Any?>>()
        val completed = withTimeoutOrNull(60_000) {
            for (link in chosen) {
                try {
                    current(expected)
                    val fetched = fetch(link["uri"] as String)
                    current(expected)
                    val ext = LinkGrab.extension(fetched.bytes, fetched.type, false)
                    val evidence = _m["schema" j "trikeshed.pdf-link-collection.v1", "inventoryCid" j inventoryCid.value,
                        "selection" j receipt["selection"], "link" j link, "requestedUrl" j link["uri"], "finalUrl" j fetched.url,
                        "contentCid" j ContentId.of(fetched.bytes).value, "contentType" j fetched.type,
                        "retrievedAt" j ctx.clock(), "reviewStatus" j "unreviewed", "action" j "collected-not-curated"]
                    val evidenceCid = ctx.casStore.put(CanonicalCbor.encodeMap(evidence)); ctx.casStore.sync()
                    val doc = target.retainSelectedPdfLink(expected, inventoryCid, link["id"] as String, ext, fetched.bytes, evidenceCid)
                    outcomes.add(_m["id" j link["id"], "project" j expected.project, "documentId" j doc,
                        "cid" j ContentId.of(fetched.bytes).value, "evidenceCid" j evidenceCid.value, "reviewStatus" j "unreviewed"])
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    outcomes.add(_m["id" j link["id"], "status" j "failed", "error" j "Link fetch deadline exceeded"])
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { outcomes.add(_m["id" j link["id"], "status" j "failed", "error" j failure.message]) }
            }
            true
        } == true
        val seen = outcomes.map { it["id"] }.toSet()
        for (link in chosen) if (link["id"] !in seen) outcomes.add(_m["id" j link["id"], "status" j "failed", "error" j "Collection deadline exceeded"])
        val sourceCurrent = corpus.document(expected.project, expected.id)?.let { it.cid == expected.cid && it.rev == expected.rev && it.seq == expected.seq } == true
        return json(_m["status" j (if (completed && sourceCurrent && outcomes.none { it["status"] == "failed" }) "collected" else "partial"),
            "sourceCurrent" j sourceCurrent,
            "selection" j receipt["selection"], "action" j "retained-unreviewed-not-model-curated", "documents" j outcomes])
    }
    private data class Fetched(val bytes: ByteArray, val type: String, val url: String)
    private suspend fun fetch(url: String): Fetched = withTimeout(20_000) {
        var at = URI(url)
        repeat(5) {
            require(at.scheme?.lowercase() in _s["http", "https"] && at.host != null && at.rawUserInfo == null && at.toString().length <= 8192) { "Invalid HTTP(S) link" }
            require(EgressAllowlist.permits(at.host.lowercase())) { "Link host is not permitted by operator egress policy" }
            val htx = currentCoroutineContext()[HtxKey] ?: throw UnsupportedOperationException("HTX collection unavailable")
            val request = (parseHtxRequest(url = at.toASCIIString(), method = HtxMethod.GET) as HtxRequest).copy(
                timeoutMs = 20_000, maxResponseBytes = 8 * 1024 * 1024,
                headers = htxHeaders("Accept" j "text/html,application/xhtml+xml,application/pdf"))
            val response = htx.request(request)
            when (response.status) {
                in 200..299 -> return@withTimeout Fetched(response.body.toArray(), response.headers.headerValue("Content-Type").orEmpty().lowercase(), at.toASCIIString())
                in 300..399 -> at = at.resolve(response.headers.headerValue("Location") ?: error("Redirect lacks Location"))
                else -> error("Linked document HTTP ${response.status}")
            }
        }
        error("Redirect limit exceeded")
    }
    companion object { const val MAX_PDF = 32 * 1024 * 1024; const val MAX_RECEIPT = 2 * 1024 * 1024 }
}
