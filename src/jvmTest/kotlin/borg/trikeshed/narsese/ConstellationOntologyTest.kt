package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class ConstellationOntologyTest {
    @TempDir lateinit var directory: Path

    @Test fun retainedSnapshotFinishesWhilePublicationIsHeldAndFreshCurationWaits() = runBlocking {
        val cas = CasStore.inMemory()
        val text = "Staff may provide care."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "report", "text/plain", "retained-source")
        val books = File(directory.toFile(), "constellations/books").apply { mkdirs() }
        File(books, "report.source").writeText(textCid.value)
        File(books, "report.sections.jsonl").writeText(jsonOf(text))
        File(books, "report.readings").apply { mkdirs() }.resolve("0.json").writeText(jsonOf(listOf(
            mapOf("text" to text, "statements" to listOf(mapOf("bearer" to "staff", "force" to "may",
                "action" to "provide", "object" to "care"))))))
        val ontology = CorpusOntology.retain(cas, "clinic", "v1", "(subclass Provider Participant)", mapOf("staff" to listOf("Provider")))
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this, cas = cas)
        val registry = linkedMapOf<String, LcncNodeRunner>()
        nodes.register(registry)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publication = nodes.curation.submit { entered.complete(Unit); release.await() }
        val inputs = mapOf("source" to DocumentCuratorCodec.source(source), "ontologyCid" to ontology.cid.value)
        val params = mapOf("book" to "report")
        var fresh: Deferred<Map<String, Any?>>? = null
        try {
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                registry.getValue(ConstellationNodes.CURATE).run(LcncNode("curate", ConstellationNodes.CURATE, params = params), inputs)
            }
            fresh = queued
            val output = withTimeout(5_000) {
                registry.getValue(ConstellationNodes.SNAPSHOT).run(LcncNode("snapshot", ConstellationNodes.SNAPSHOT, params = params), inputs)
            }
            assertEquals(true, output["retainedParser"])
            assertEquals(1, (output["relationships"] as List<*>).size)
            assertFalse(publication.isCompleted, "Retained snapshot must not need the publication lane")
            assertFalse(queued.isCompleted, "Fresh curation must keep its publication ordering")
            val receipt = ContentId(output["curationReceiptCid"] as String)
            assertNotNull(cas.get(receipt))
            release.complete(Unit)
            publication.await().getOrThrow()
            queued.await()
        } finally {
            release.complete(Unit)
            publication.await().getOrThrow()
            fresh?.join()
            nodes.drain()
        }
    }

    @Test fun retainedParserIsReclassifiedWithoutGlobalSumoAndUnreviewedJoinIsRefused() = runBlocking {
        val cas = CasStore.inMemory()
        val text = "Staff may provide care.\nHuman may help."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "report", "text/plain", "retained-source")
        val books = File(directory.toFile(), "constellations/books").apply { mkdirs() }
        File(books, "report.source").writeText(textCid.value)
        File(books, "report.sections.jsonl").writeText(jsonOf(text))
        val readings = File(books, "report.readings").apply { mkdirs() }
        // A retained syntax result may carry legacy SUMO labels. The selected customer's mapping must replace all of them.
        File(readings, "0.json").writeText(jsonOf(listOf(
            mapOf("text" to "Staff may provide care.", "concepts" to listOf(mapOf("word" to "Staff", "lemma" to "staff", "class" to "Human")),
                "statements" to listOf(mapOf("bearer" to "staff", "force" to "may", "action" to "provide", "object" to "care", "class" to "Human"))),
            mapOf("text" to "Human may help.", "concepts" to listOf(mapOf("word" to "Human", "lemma" to "human", "class" to "Human")),
                "statements" to listOf(mapOf("bearer" to "human", "force" to "may", "action" to "help", "class" to "Human")))
        )))
        val ontology = CorpusOntology.retain(cas, "clinic", "v1", "(subclass Provider Participant)", mapOf("staff" to listOf("Provider")))
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this, cas = cas)
        val registry = linkedMapOf<String, LcncNodeRunner>()
        nodes.register(registry)
        try {
            val output = registry.getValue(ConstellationNodes.SNAPSHOT).run(
                LcncNode("snapshot", ConstellationNodes.SNAPSHOT, params = mapOf("book" to "report")),
                mapOf("source" to DocumentCuratorCodec.source(source), "ontologyCid" to ontology.cid.value))
            assertEquals(true, output["retainedParser"])
            val relationships = output["relationships"] as List<*>
            assertEquals(2, relationships.size)
            val staff = relationships[0] as Map<*, *>
            val human = relationships[1] as Map<*, *>
            assertEquals(listOf("Provider"), (staff["ontologyMapping"] as Map<*, *>)["classes"])
            assertEquals("unresolved", (human["ontologyMapping"] as Map<*, *>)["status"])
            val candidate = nodes.reviewCandidate(ContentId(output["curationReceiptCid"] as String), listOf(staff["id"] as String))
            assertTrue(nodes.reviewCurrent(candidate))
            val section = nodes.section(output["book"] as String, "0")
            assertEquals("v1", section["ontologyVersion"])
            assertEquals("unreviewed", section["reviewStatus"])
            assertFailsWith<IllegalArgumentException> {
                registry.getValue(ConstellationNodes.JOIN).run(LcncNode("join", ConstellationNodes.JOIN,
                    params = mapOf("constellation" to "test")), mapOf("book" to output["book"]))
            }
            val frames = linkedMapOf<Long, ByteArray>()
            val log = object : DurableAppendLog {
                override fun append(sequence: Long, payload: ByteArray): Long {
                    frames[sequence] = payload.copyOf(); return sequence
                }
                override fun flush() {}
                override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
                    for ((sequence, bytes) in frames) onFrame(sequence, bytes.copyOf())
                    return frames.keys.lastOrNull() ?: 0
                }
                override fun injectCorruptionAfter(sequence: Long) = error("Unused fault injection")
            }
            val review = CurationReview(cas, log,
                { kind, cid, ids -> require(kind == "book"); nodes.reviewCandidate(cid, ids) },
                { nodes.reviewCurrent(it) }, { 1L })
            review.open(this)
            nodes.reviewRelease = review::consume
            try {
                val frozen = review.snapshot("book", candidate.curationReceiptCid, listOf(staff["id"] as String))
                val snapshotCid = ContentId(frozen["snapshotCid"] as String)
                val approval = withContext(ReviewPrincipal("ontology-fixture", synthetic = true)) {
                    review.decide(snapshotCid, (frozen["revision"] as Number).toLong(), "approve")
                }
                val release = review.release(snapshotCid, ContentId(approval["decisionCid"] as String),
                    (approval["revision"] as Number).toLong())
                val joined = registry.getValue(ConstellationNodes.JOIN).run(
                    LcncNode("join", ConstellationNodes.JOIN, params = mapOf("constellation" to "test")),
                    mapOf("releaseCid" to release["releaseCid"]))
                val exported = (joined["relationships"] as List<*>).single() as Map<*, *>
                assertEquals("staff", exported["subject"])
                assertEquals(staff["proposalCid"], exported["proposalCid"])
                assertEquals(approval["decisionCid"], exported["approvalCid"])
                assertEquals(ontology.cid.value, exported["ontologyCid"])
                assertEquals(joined["outputCid"], exported["outputCid"])
                assertEquals("Staff may provide care.", (exported["quotation"] as Map<*, *>)["quote"])
                assertEquals(true, joined["synthetic"])
                assertNotNull(cas.get(ContentId(joined["outputCid"] as String)))
                assertFailsWith<IllegalArgumentException> {
                    registry.getValue(ConstellationNodes.JOIN).run(
                        LcncNode("repeat", ConstellationNodes.JOIN, params = mapOf("constellation" to "test")),
                        mapOf("releaseCid" to release["releaseCid"]))
                }
            } finally { review.drain() }
        } finally { nodes.drain() }
    }
}
