package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class ConstellationOntologyTest {
    @TempDir lateinit var directory: Path

    private class ReviewStore : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
            val bytes = blobs[cid] ?: return null
            require(bytes.size <= maxBytes)
            return bytes.copyOf()
        }
    }

    @Test fun restoredMembersExcludeCustomerBooksWithoutTreatingReleaseAsWholeBookApproval() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        val books = File(nodes.root, "books")
        fun saved(stem: String, name: String, provenance: Map<String, String> = emptyMap()) {
            File(books, "$stem.json").writeText(jsonOf(mapOf("name" to name, "work" to "fixture", "date" to "1900",
                "headings" to emptyList<String>(), "statements" to emptyList<Any>(), "support" to emptyList<Any>(),
                "provenance" to provenance)))
        }
        saved("legacy", "legacy")
        saved("customer", "customer", mapOf("ontologyCid" to ContentId.of("ontology".encodeToByteArray()).value))
        // Even if provenance was lost, an extant customer-curation sidecar keeps it out.
        saved("damaged", "damaged")
        File(books, "damaged.curation").writeText("unverified receipt")
        // Names with the same sanitized filename must not silently load another book.
        saved("alias-", "different")
        val raw = listOf("legacy", "customer", "damaged", "alias?", "missing")
        File(nodes.root, "mixed.members").writeText(raw.joinToString("\n", postfix = "\n"))
        val registry = linkedMapOf<String, LcncNodeRunner>()
        nodes.register(registry)
        try {
            nodes.restore()
            val brief = registry.getValue(ConstellationNodes.BRIEF).run(
                LcncNode("brief", ConstellationNodes.BRIEF, params = mapOf("constellation" to "mixed")), emptyMap())
            assertTrue(brief.getValue("summary").toString().contains("1 books (legacy)"))
            assertEquals(raw.drop(1), brief["excludedMembers"])
            assertEquals(raw, nodes.members("mixed"), "Raw membership is retained for recovery and collision checks")
            assertEquals("mixed", nodes.constellationOf("customer"), "Overwrite preflight must still see raw membership")
            val join = registry.getValue(ConstellationNodes.JOIN)
            for (name in listOf("customer", "damaged", "alias?")) {
                assertFailsWith<IllegalArgumentException> {
                    join.run(LcncNode("join", ConstellationNodes.JOIN, params = mapOf("constellation" to "mixed")),
                        mapOf("book" to name))
                }
            }
        } finally { nodes.drain() }
    }

    @Test fun sourceOnlyCurateRunsThroughLcncAndInvalidLegacyInputsCannotReplaceBook() = runBlocking {
        val cas = CasStore.inMemory()
        val text = "Staff may provide care."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "report", "text/plain", "retained-source")
        val books = File(directory.toFile(), "constellations/books").apply { mkdirs() }
        val marker = File(books, "report.source").apply { writeText(textCid.value) }
        File(books, "report.sections.jsonl").writeText(jsonOf(text))
        File(books, "report.readings").apply { mkdirs() }.resolve("0.json").writeText(jsonOf(listOf(
            mapOf("text" to text, "statements" to listOf(mapOf("bearer" to "staff", "force" to "may",
                "action" to "provide", "object" to "care"))))))
        val ontology = CorpusOntology.retain(cas, "clinic", "v1", "(subclass Provider Participant)",
            mapOf("staff" to listOf("Provider")))
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this, cas = cas)
        val registry = linkedMapOf<String, LcncNodeRunner>()
        nodes.register(registry)
        fun program(sourcePort: Boolean, textPort: Boolean, ontologyPort: Boolean): LcncProgram {
            val allNodes = mutableListOf<LcncNode>()
            val wires = mutableListOf<LcncWire>()
            fun bind(id: String, kind: String, port: String) {
                allNodes.add(LcncNode(id, "scope.in", mapOf("name" to id, "kind" to kind)))
                wires.add(LcncWire(id, "value", "curate", port))
            }
            if (sourcePort) bind("source", "DocumentSource", "source?")
            // Existing saved graphs use the unadorned text port.
            if (textPort) bind("text", "text", "text")
            if (ontologyPort) bind("ontology", "id", "ontologyCid?")
            allNodes.add(LcncNode("curate", ConstellationNodes.CURATE, params = mapOf("book" to "report")))
            return LcncProgram("book-input", allNodes.toSeries(), wires.toSeries())
        }
        try {
            val runner = LcncRunner(registry)
            val result = runner.runProcedure(program(true, false, true), mapOf(
                "source" to DocumentCuratorCodec.source(source), "ontology" to ontology.cid.value))
            val curated = result.nodeOutputs.getValue("curate")
            assertEquals("unreviewed", curated["reviewStatus"])
            assertEquals(1, (curated["relationships"] as List<*>).size)
            assertEquals(true, curated["retainedParser"])

            assertFailsWith<IllegalArgumentException> { runner.runProcedure(program(false, false, false)) }
            assertFailsWith<IllegalArgumentException> {
                runner.runProcedure(program(true, false, false), mapOf("source" to DocumentCuratorCodec.source(source)))
            }
            assertFailsWith<IllegalArgumentException> {
                runner.runProcedure(program(true, true, true), mapOf("source" to DocumentCuratorCodec.source(source),
                    "text" to "different text", "ontology" to ontology.cid.value))
            }
            assertEquals(textCid.value, marker.readText(), "Invalid input must not invalidate the retained source")

            // The contract must continue to accept previously saved unadorned text wires.
            var seen: String? = null
            val probe = LcncRunner(mapOf(ConstellationNodes.CURATE to LcncNodeRunner { _, inputs ->
                seen = inputs["text"] as? String
                emptyMap()
            }))
            probe.runProcedure(program(false, true, false), mapOf("text" to text))
            assertEquals(text, seen)
        } finally { nodes.drain() }
    }

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
        val cas = ReviewStore()
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
            val selected = candidate.selections[0]
            val forged = candidate.copy(selections = s_[selected.copy(value = selected.value + mapOf("subject" to "forged"))])
            assertFalse(nodes.reviewCurrent(forged), "A matching quotation and receipt CID do not bind a changed tuple")
            // Reproduce a snapshot created by a pre-fix resolver: the source and receipt IDs are
            // real, but the selected value differs from the retained receipt. On replay the new
            // current check must not promote it, even if the old ledger already issued a permit.
            val oldFrames = linkedMapOf<Long, ByteArray>()
            val oldLog = object : DurableAppendLog {
                override fun append(sequence: Long, payload: ByteArray): Long { oldFrames[sequence] = payload.copyOf(); return sequence }
                override fun flush() {}
                override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
                    for ((sequence, bytes) in oldFrames) onFrame(sequence, bytes.copyOf())
                    return oldFrames.keys.lastOrNull() ?: 0
                }
                override fun injectCorruptionAfter(sequence: Long) = error("Unused fault injection")
            }
            val oldReview = CurationReview(cas, oldLog, { _, _, _ -> forged }, { true }, { 1L }, ConditionReviewPolicy.LEGACY)
            oldReview.open(this)
            val oldSnapshot = oldReview.snapshot("book", forged.curationReceiptCid, listOf(selected.id))
            val oldSnapshotCid = ContentId(oldSnapshot["snapshotCid"] as String)
            val oldApproval = withContext(ReviewPrincipal("old-fixture", synthetic = true)) {
                oldReview.decide(oldSnapshotCid, (oldSnapshot["revision"] as Number).toLong(), "approve")
            }
            val oldRelease = oldReview.release(oldSnapshotCid, ContentId(oldApproval["decisionCid"] as String),
                (oldApproval["revision"] as Number).toLong())
            oldReview.drain()
            val replay = CurationReview(cas, oldLog, { _, cid, ids -> nodes.reviewCandidate(cid, ids) },
                { nodes.reviewCurrent(it) }, { 2L }, ConditionReviewPolicy.STRICT_BOOK)
            replay.open(this)
            try {
                val oldView = replay.inspect(oldSnapshotCid)
                assertEquals(false, oldView["sourceCurrent"])
                assertEquals(false, oldView["current"])
                assertFailsWith<IllegalArgumentException> { replay.consume(ContentId(oldRelease["releaseCid"] as String)) }
            } finally { replay.drain() }
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
                { nodes.reviewCurrent(it) }, { 1L }, ConditionReviewPolicy.LEGACY)
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
