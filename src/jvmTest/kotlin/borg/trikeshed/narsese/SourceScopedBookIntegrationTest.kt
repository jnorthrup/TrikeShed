package borg.trikeshed.narsese

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.forge.server.DocumentReviewWire
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Synthetic retained book through the production resolver and currentness checks. */
class SourceScopedBookIntegrationTest {
    @TempDir lateinit var directory: Path

    private fun cid(value: Map<String, Any?>, key: String) = ContentId(value.getValue(key) as String)
    private fun revision(value: Map<String, Any?>) = (value.getValue("revision") as Number).toLong()

    @Test fun retainedBookReviewQueriesAndRejectsChangedSourceOrOntologyReceipt() = runBlocking {
        withTimeout(30_000) {
            for (scenario in _l["current", "source-changed", "ontology-replaced"]) {
                val dir = Files.createTempDirectory(directory, "source-scoped-")
                val files = JvmFileOperations()
                val cas = FileCasStore(files, dir.resolve("cas").toString())
                val owner = SupervisorJob(coroutineContext[Job])
                var activeNodes: ConstellationNodes? = null
                var activeWire: DocumentReviewWire? = null
                try {
                    val scope = CoroutineScope(coroutineContext + owner + files)
                    val store = CouchStoreFactory.casBacked(cas)
                    val ctx = ModuleContext(Couch("source-scoped-proof", store, cas), ReteNetwork(), ReteProductionRegistry(),
                        null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
                        ModuleRouteRegistry(), scope, { System.currentTimeMillis() }, dir.toFile())
                    val text = "Synthetic clinic may provide care."
                    val textCid = cas.put(text.encodeToByteArray())
                    val source = DocumentSource(textCid, textCid, text, "proof", "text/plain", "synthetic-fixture")
                    val clauses = s_[BookClause(NormClause("clinic", "may", true, "provide", "care", null,
                        text, null), 0, text.length, 0)]
                    val ontology = CorpusOntology.retain(cas, "proof", "v1", "(subclass Provider Participant)",
                        _m["clinic" j _l["Provider"]])
                    val receipt = BookCurationReceipt.retain(cas, "proof", source, ontology, clauses, "synthetic-fixture")
                    val row = (BookCurationReceipt.read(cas, receipt)["relationships"] as List<*>).single() as Map<*, *>
                    val selection = row["id"] as String
                    val bookDir = dir.resolve("constellations/books")
                    Files.createDirectories(bookDir)
                    val sourceMarker = bookDir.resolve("proof.source")
                    val curationMarker = bookDir.resolve("proof.curation")
                    Files.writeString(sourceMarker, textCid.value)
                    Files.writeString(curationMarker, receipt.value)
                    val nodes = ConstellationNodes(dir.toFile(), ctx.blackboard, scope = scope, cas = cas)
                    activeNodes = nodes
                    val wire = DocumentReviewWire.open(ctx, InMemoryProjectCorpus(), nodes)
                    activeWire = wire
                    val review = wire.review
                    val resolved = nodes.reviewCandidate(receipt, _l[selection])
                    assertTrue(nodes.reviewCurrent(resolved))
                    val snapshot = review.snapshot("book", receipt, _l[selection])
                    val snapshotCid = cid(snapshot, "snapshotCid")
                    val principal = ReviewPrincipal("synthetic:book-reviewer", synthetic = true)
                    val draft = withContext(principal) {
                        review.assess(snapshotCid, revision(snapshot),
                            _l[ConditionAssessment(snapshotCid, selection, ConditionAssessmentState.UNCONDITIONAL)])
                    }
                    val approved = withContext(principal) {
                        review.decide(snapshotCid, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
                    }
                    val release = cid(review.release(snapshotCid, cid(approved, "decisionCid"), revision(approved)), "releaseCid")
                    val request = SourceQuery(textCid, textCid, "urn:trikeshed:source-text:${textCid.value}",
                        "clinic", "provide", "care", modality = "may", polarity = true)
                    when (scenario) {
                        "current" -> {
                            val result = SourceScopedQuery.query(review, cas, release, request)
                            assertEquals(SourceQueryStatus.MATCH, result.status)
                            val match = result.matches.single()
                            assertEquals(text, match.quote)
                            assertEquals(text, text.substring(match.begin, match.end))
                            assertEquals(selection, match.selectionId)
                            assertEquals(ontology.cid, match.ontologyCid)
                            assertEquals("unconditional", match.conditionState)
                        }
                        "source-changed" -> {
                            Files.writeString(sourceMarker, cas.put("changed source".encodeToByteArray()).value)
                            assertFalse(nodes.reviewCurrent(resolved))
                            assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(review, cas, release, request) }
                        }
                        else -> {
                            val newOntology = CorpusOntology.retain(cas, "proof", "v2", "(subclass Provider Participant)",
                                _m["clinic" j _l["Provider"]])
                            val newReceipt = BookCurationReceipt.retain(cas, "proof", source, newOntology, clauses, "synthetic-fixture")
                            assertNotEquals(ontology.cid, newOntology.cid)
                            assertNotEquals(receipt, newReceipt)
                            Files.writeString(curationMarker, newReceipt.value)
                            assertFalse(nodes.reviewCurrent(resolved))
                            assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(review, cas, release, request) }
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        try {
                            try { activeWire?.drain() } finally { activeWire?.close() }
                        } finally {
                            try { activeNodes?.drain() } finally { owner.cancelAndJoin() }
                        }
                    }
                }
            }
        }
    }
}
