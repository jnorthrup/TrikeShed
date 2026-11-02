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
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Genuine retained book + durable review owner; no supplied approval-map callback. */
class ReviewedTaxonomyWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun authenticatedCurrentReadExcludesNormsWithoutSpendingItsPermit(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            DocumentReviewWire.taxonomyRequest("[".repeat(17) + "0" + "]".repeat(17))
        }
        val files = JvmFileOperations()
        val cas = FileCasStore(files, directory.resolve("cas").toString())
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("taxonomy-read", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val text = "A subclass B."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "taxonomy", "text/plain", "manual-synthetic-fixture")
        val ontology = CorpusOntology.retain(cas, "fixture", "v1", "(subclass A B)", emptyMap())
        val receipt = BookCurationReceipt.retain(cas, "taxonomy", source, ontology,
            s_[BookClause(NormClause("A", "generic", true, "subclass", "B", null, text, null), 0, text.length, 0)], "manual-fixture")
        val selection = ((BookCurationReceipt.read(cas, receipt)["relationships"] as List<*>).single() as Map<*, *>)["id"] as String
        val bookDir = directory.resolve("constellations/books"); Files.createDirectories(bookDir)
        val sourceMarker = bookDir.resolve("taxonomy.source")
        Files.writeString(sourceMarker, textCid.value)
        Files.writeString(bookDir.resolve("taxonomy.curation"), receipt.value)
        val documents = directory.resolve("documents"); Files.createDirectories(documents)
        val token = "synthetic-taxonomy-read-token"
        Files.writeString(documents.resolve("review-principals.json"), jsonOf(mapOf("principals" to listOf(mapOf(
            "tokenSha256" to ContentId.of(token.encodeToByteArray()).hex, "id" to "synthetic:analyst", "synthetic" to true)))))
        val books = ConstellationNodes(directory.toFile(), ctx.blackboard, scope = scope, cas = cas)
        var wire: DocumentReviewWire? = null
        try {
            wire = DocumentReviewWire.open(ctx, InMemoryProjectCorpus(), books)
            suspend fun request(suffix: String, body: Map<String, Any?>, auth: String? = token, status: Int = 200): Map<String, Any?> {
                val path = "/api/documents/review/$suffix"
                val message = "POST $path HTTP/1.1\r\n" + (auth?.let { "Authorization: Bearer $it\r\n" } ?: "") + "\r\n" + jsonOf(body)
                val response = assertNotNull(ctx.routes.match(path)?.route("POST", path, message, null))
                assertEquals(status, response.status, response.body)
                return reifyMap(response.body)
            }
            fun cid(map: Map<String, Any?>, key: String) = ContentId(map[key] as String)
            fun revision(map: Map<String, Any?>) = (map["revision"] as Number).toLong()
            val snapshot = request("snapshot", mapOf("kind" to "book", "receiptCid" to receipt.value, "selection" to listOf(selection)))
            val snapshotCid = cid(snapshot, "snapshotCid")
            val draft = request("assess", mapOf("snapshotCid" to snapshotCid.value, "expectedRevision" to snapshot["revision"],
                "assessments" to listOf(mapOf("selectionId" to selection, "state" to "unconditional", "spans" to emptyList<Any>()))))
            val approved = request("decision", mapOf("snapshotCid" to snapshotCid.value, "expectedRevision" to draft["revision"],
                "action" to "approve", "expectedAssessmentCid" to draft["assessmentCid"]))
            fun goal(rev: Any?) = mapOf("snapshotCid" to snapshotCid.value, "expectedRevision" to rev,
                "goal" to mapOf("predicate" to "subclass", "subject" to "A", "object" to "B"))
            request("taxonomy-plan", goal(approved["revision"]), auth = null, status = 401)
            request("taxonomy-plan", goal(approved["revision"]), auth = "forged", status = 401)
            request("taxonomy-plan", goal(approved["revision"]) + ("review" to "approved"), status = 409)
            val wal = documents.resolve("review.wal")
            val walSize = Files.size(wal)
            val getPath = "/api/documents/review/taxonomy-plan"
            val get = assertNotNull(ctx.routes.match(getPath)?.route("GET", getPath,
                "GET $getPath HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n", null))
            assertEquals(405, get.status)
            assertEquals(walSize, Files.size(wal), "Refused GET must not append a review event")
            val first = request("taxonomy-plan", goal(approved["revision"]))
            val second = request("taxonomy-plan", goal(approved["revision"]))
            assertEquals(walSize, Files.size(wal), "Read must not append a review event")
            assertEquals(first["readCid"], second["readCid"])
            assertEquals(first["plan"], second["plan"])
            assertEquals("UNKNOWN", first["status"])
            assertEquals(false, first["positivePathProven"])
            assertEquals("asserted-taxonomy-source-format-unavailable", first["mappingStatus"])
            val counters = first["counters"] as Map<*, *>
            assertEquals(0, (counters["eligible"] as Number).toInt())
            val row = (first["selected"] as List<*>).single() as Map<*, *>
            assertEquals(selection, row["selectionId"])
            assertEquals("presumed", (row["value"] as Map<*, *>)["modality"])
            assertEquals(text, (row["quotation"] as Map<*, *>)["quote"])
            assertTrue((row["exclusionReasons"] as List<*>).isNotEmpty())
            val hornRequest = mapOf("snapshotCid" to snapshotCid.value, "expectedRevision" to approved["revision"],
                "goal" to "needsMapping")
            request("horn-input", hornRequest, auth = null, status = 401)
            request("horn-input", hornRequest + ("review" to "approved"), status = 409)
            request("horn-input", hornRequest + ("goal" to "applyObligation"), status = 409)
            val horn = request("horn-input", hornRequest)
            assertEquals("NOT_EXECUTED", horn["status"])
            assertEquals("review-record-observations", horn["scope"])
            assertEquals("UNKNOWN", horn["worldObligationApplicability"])
            assertEquals(walSize, Files.size(wal), "Preparing native input must not spend a permit or append an event")
            val inputJson = horn["inputJson"] as String
            assertEquals(ContentId.of(inputJson.encodeToByteArray()).value, horn["inputCid"])
            val nativeInput = reifyMap(inputJson)
            assertTrue((nativeInput["facts"] as List<*>).all { (it as List<*>).first() !in setOf("subclass", "instance") })
            val hornPath = "/api/documents/review/horn-input"
            assertEquals(405, assertNotNull(ctx.routes.match(hornPath)?.route("GET", hornPath,
                "GET $hornPath HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n", null)).status)
            assertNull(ctx.beliefBag)
            assertFalse(first.keys.any { it in setOf("releaseCid", "admissionCid") })
            val release = request("release", mapOf("snapshotCid" to snapshotCid.value,
                "approvalCid" to approved["decisionCid"], "expectedRevision" to approved["revision"]))
            request("taxonomy-plan", goal(approved["revision"]), status = 409)
            request("taxonomy-plan", goal(release["revision"]))
            wire.review.consume(cid(release, "releaseCid"))
            assertFailsWith<IllegalArgumentException> { wire.review.consume(cid(release, "releaseCid")) }
            val state = wire.review.inspect(snapshotCid)
            assertEquals("consume", state["state"])
            request("taxonomy-plan", goal(revision(state)))
            Files.writeString(sourceMarker, cas.put("changed".encodeToByteArray()).value)
            request("taxonomy-plan", goal(revision(state)), status = 409)
            request("horn-input", hornRequest + ("expectedRevision" to revision(state)), status = 409)
        } finally {
            withContext(NonCancellable) {
                try { wire?.drain() } finally {
                    try { wire?.close(); books.drain() } finally { owner.cancelAndJoin() }
                }
            }
        }
    }
}
