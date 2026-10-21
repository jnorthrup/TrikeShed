package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class DocumentReviewWireTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var directory: Path

    val token = "synthetic-review-boundary-token"
    val text = "Synthetic clinic provides services."

    class Session(val rig: DocumentCurationWireTest.Rig, val corpus: InMemoryProjectCorpus,
        val books: ConstellationNodes, val wire: DocumentReviewWire, val receipt: ContentId)

    suspend fun fixture(body: suspend (Session) -> Unit): Unit = coroutineScope {
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val rig = DocumentCurationWireTest.Rig(scope, directory.toFile(), false)
        val documents = directory.resolve("documents").toFile().apply { mkdirs() }
        documents.resolve("review-principals.json").writeText(jsonOf(mapOf("principals" to listOf(mapOf(
            "tokenSha256" to ContentId.of(token.encodeToByteArray()).hex,
            "id" to "synthetic:analyst", "synthetic" to true)))))
        val corpus = InMemoryProjectCorpus()
        corpus.put("pilot", "assertion.txt", text.encodeToByteArray())
        val source = DocumentCurationLegos.projectSource(rig.cas, corpus, corpus.document("pilot", "assertion.txt")!!)
        val proposal = DocumentProposal("synthetic retained proposal", "Synthetic clinic", "provide", "services",
            quote = text, begin = 0, end = text.length)
        val record = DocumentCurationRecord(source, null, null, "synthetic", s_[proposal], emptySeriesOf(),
            toolOntology = s_["synthetic:Clinic", "synthetic:Service"])
        val receipt = rig.cas.put(DocumentCuratorCodec.encode(record))
        val books = ConstellationNodes(directory.toFile(), rig.ctx.blackboard, scope = scope, cas = rig.cas)
        books.register(rig.ctx.lcncRunners)
        var wire: DocumentReviewWire? = null
        try {
            wire = DocumentReviewWire.open(rig.ctx, corpus, books)
            body(Session(rig, corpus, books, wire, receipt))
        } finally {
            try { wire?.drain(); wire?.close(); books.drain() }
            finally { owner.cancelAndJoin() }
        }
    }

    suspend fun request(session: Session, method: String, suffix: String, body: Map<String, Any?> = emptyMap(),
        authorization: String? = null, status: Int = 200): Map<String, Any?> {
        val path = "/api/documents/review$suffix"
        val message = buildString {
            append("$method $path HTTP/1.1\r\nHost: synthetic.local\r\n")
            if (authorization != null) append("Authorization: Bearer $authorization\r\n")
            append("\r\n")
            if (method == "POST") append(jsonOf(body))
        }
        val response = assertNotNull(session.rig.ctx.routes.match(path)?.route(method, path, message, null))
        assertEquals(status, response.status, response.body)
        return reifyMap(response.body)
    }

    suspend fun snapshot(session: Session) = request(session, "POST", "/snapshot", mapOf(
        "kind" to "document", "receiptCid" to session.receipt.value, "selection" to listOf("proposal:0")))

    @Test fun authenticatedHttpDecisionReleasesOnlyItsRetainedSelectionToTheSink(): Unit = runBlocking {
        fixture { session ->
            assertNull(session.rig.ctx.beliefBag, "Inspection and review do not need a model or belief bag")
            assertEquals("authenticated_reviewer_required", request(session, "GET", "/session", status = 401)["error"])
            request(session, "GET", "/session", authorization = "forged-token", status = 401)
            val actor = request(session, "GET", "/session", authorization = token)
            assertEquals("synthetic:analyst", actor["reviewer"])
            assertEquals(true, actor["synthetic"])
            val selected = snapshot(session)
            val decision = mapOf("snapshotCid" to selected["snapshotCid"], "expectedRevision" to selected["revision"],
                "action" to "approve")
            request(session, "POST", "/decision", decision, status = 401)
            request(session, "POST", "/decision", decision + ("reviewer" to "synthetic:analyst"), status = 401)
            request(session, "POST", "/decision", decision + ("reviewer" to "another:analyst"), token, 409)
            val fractional = request(session, "POST", "/decision", decision + ("expectedRevision" to 1.5), token, 409)
            assertContains(assertIs<String>(fractional["error"]), "whole number")
            val unchanged = request(session, "GET", "?snapshotCid=${selected["snapshotCid"]}")
            assertEquals(selected["revision"], unchanged["revision"])
            assertEquals("snapshot", unchanged["state"])
            val approved = request(session, "POST", "/decision", decision, token)
            assertEquals("approve", approved["state"])
            assertEquals(actor["reviewer"], approved["reviewer"])
            assertEquals(true, approved["synthetic"])
            val release = mapOf("snapshotCid" to selected["snapshotCid"], "approvalCid" to approved["decisionCid"],
                "expectedRevision" to approved["revision"])
            request(session, "POST", "/release", release, status = 401)
            val released = request(session, "POST", "/release", release, token)
            val sink = session.rig.ctx.lcncRunners.getValue(ConstellationNodes.JOIN)
            val inputs = mapOf("releaseCid" to released["releaseCid"])
            val node = LcncNode("results", ConstellationNodes.JOIN, params = mapOf("constellation" to "synthetic-pilot"))
            val result = sink.run(node, inputs)
            assertEquals("approved", result["reviewStatus"])
            assertEquals(true, result["synthetic"])
            val relationship = assertIs<Map<*, *>>(assertIs<List<*>>(result["relationships"]).single())
            assertEquals("services", relationship["object"])
            assertEquals(text, assertIs<Map<*, *>>(relationship["quotation"])["quote"])
            assertEquals(session.receipt.value, relationship["curationReceiptCid"])
            assertEquals(actor["reviewer"], relationship["reviewer"])
            assertEquals(released["releaseCid"], relationship["releaseCid"])
            val output = ContentId(assertIs<String>(result["outputCid"]))
            assertEquals(output, ContentId.of(assertNotNull(session.rig.cas.get(output))))
            assertFailsWith<IllegalArgumentException> { sink.run(node, inputs) }
            assertEquals("consume", request(session, "GET", "?snapshotCid=${selected["snapshotCid"]}")["state"])
        }
    }

    @Test fun changingOriginalRevisionAfterSnapshotPreventsHttpApproval(): Unit = runBlocking {
        fixture { session ->
            val selected = snapshot(session)
            // Even identical bytes are a new source revision and invalidate the retained review.
            session.corpus.put("pilot", "assertion.txt", text.encodeToByteArray())
            val refusal = request(session, "POST", "/decision", mapOf("snapshotCid" to selected["snapshotCid"],
                "expectedRevision" to selected["revision"], "action" to "approve"), token, 409)
            assertContains(assertIs<String>(refusal["error"]), "no longer current")
            val state = request(session, "GET", "?snapshotCid=${selected["snapshotCid"]}")
            assertEquals(false, state["sourceCurrent"])
            assertEquals("snapshot", state["state"])
            assertEquals(selected["revision"], state["revision"])
        }
    }
}
