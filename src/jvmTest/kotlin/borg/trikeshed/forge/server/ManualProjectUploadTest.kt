package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.narsese.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ManualProjectUploadTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private class Rig(val root: Path) {
        val files = JvmFileOperations()
        val cas = FileCasStore(files, root.resolve("cas").toString())
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val index = CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store)))
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(files, gateway, index, cas, null, projectDbs = registry,
            ledgerFile = root.resolve("projects.tsv").toFile(), filesRoot = root.resolve("files").toFile())
        val corpus = JvmProjectCorpus(registry, scopes)
        val wire = ProjectDbWire(registry, scopes)
        suspend fun request(path: String, bytes: ByteArray = ByteArray(0), expected: Int = 200): Map<String, Any?> {
            val raw = "POST $path HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n".encodeToByteArray() + bytes
            val response = assertNotNull(wire.route("POST", path, raw, null))
            assertEquals(expected, response.status, response.body)
            return reifyMap(response.body)
        }
    }

    private fun batch(rows: List<Join<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        for ((name, bytes) in rows) {
            val path = name.encodeToByteArray()
            for (i in 3 downTo 0) out.write(path.size ushr (i * 8))
            out.write(path)
            for (i in 7 downTo 0) out.write((bytes.size.toLong() ushr (i * 8)).toInt())
            out.write(bytes)
        }
    }.toByteArray()

    @Test fun binaryBatchSurvivesRestartAsManualAndGuardCorruptionBlocksIntake(): Unit = runBlocking {
        val first = Rig(directory)
        val bytes = _a[0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte(), 0.toByte(), 0xff.toByte(), 0x0a.toByte()]
        try {
            val begun = first.request("/_project/manual-docs/begin?intake=manual")
            assertEquals("manual", begun["intake"])
            assertEquals(CurationIntake.Mode.Manual, first.scopes.intakeMode("manual-docs"))
            val stored = first.request("/_project/manual-docs/putBatch?intake=manual", batch(_l["one.pdf" j bytes, "two.pdf" j (bytes + 1)]))
            assertEquals(2, (stored["stored"] as Number).toInt())
            assertEquals(0, (stored["failed"] as Number).toInt())
            val pdb = assertNotNull(first.registry.get("manual-docs"))
            for ((id, raw) in _l["one.pdf" j bytes, "two.pdf" j (bytes + 1)]) {
                val attachment = assertNotNull(pdb.gateway.getAttachmentRef(id))
                assertEquals(ContentId.of(raw), attachment.contentId)
                assertContentEquals(raw, first.cas.get(attachment.contentId))
            }
            first.request("/_project/manual-docs/begin?intake=manual", expected = 400)
            assertEquals(CurationIntake.Mode.Manual, first.scopes.intakeMode("manual-docs"))
            first.request("/_project/unknown/begin?intake=automatic", expected = 400)
            assertNull(first.registry.get("unknown"))
            first.request("/_project/legacy/begin")
            first.request("/_project/legacy/put?intake=manual&path=no.pdf", bytes, 409)
            first.request("/_project/legacy/putBatch?intake=manual", batch(_l["no.pdf" j bytes]), 409)
            assertNull(first.registry.get("legacy")?.gateway?.getAttachmentRef("no.pdf"))
        } finally { first.scopes.drain() }

        suspend fun verifyRestart(blocked: Boolean) {
            val restarted = Rig(directory)
            val extracts = AtomicInteger(); val models = AtomicInteger()
            val counted = object : ProjectCorpus by restarted.corpus {
                override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText? {
                    extracts.incrementAndGet(); error("Manual originals must not be extracted on resume")
                }
            }
            val intake = CurationIntake(counted, { _m[ConstellationNodes.CURATE j LcncNodeRunner { _, _ ->
                models.incrementAndGet(); error("Manual originals must not invoke curation/model") }] }, { null }, this,
                settled = {}, mode = { project, _ -> restarted.scopes.intakeMode(project) })
            try {
                restarted.scopes.onDocument = { p, id, provider -> intake.offer(p, id, provider) }
                assertTrue(restarted.scopes.remountLedger() >= 1)
                if (blocked) assertIs<CurationIntake.Mode.Blocked>(restarted.scopes.intakeMode("manual-docs"))
                else assertEquals(CurationIntake.Mode.Manual, restarted.scopes.intakeMode("manual-docs"))
                intake.resume(); intake.drain()
                assertEquals(0, extracts.get()); assertEquals(0, models.get())
                val attachment = assertNotNull(restarted.registry.get("manual-docs")?.gateway?.getAttachmentRef("one.pdf"))
                assertEquals(ContentId.of(bytes), attachment.contentId)
                assertContentEquals(bytes, restarted.cas.get(attachment.contentId))
            } finally { intake.drain(); restarted.scopes.drain() }
        }
        verifyRestart(false)
        Files.writeString(directory.resolve("project-intake-policy/manual-docs.guard"), "corrupt")
        verifyRestart(true)
    }

    @Test fun explicitSelectionQueuesManualOnlyAndPreservesReviewAndBlockedFences(): Unit = runBlocking {
        val text = "Synthetic retained source."
        val cid = ContentId.of(text.encodeToByteArray())
        val ontology = ContentId.of("synthetic ontology".encodeToByteArray())
        for (policy in _l[CurationIntake.Mode.Manual, CurationIntake.Mode.ReviewRequired(ontology), CurationIntake.Mode.Blocked("bad guard")]) {
            val extracts = AtomicInteger(); val curates = AtomicInteger(); val joins = AtomicInteger()
            val source = DocumentSource(cid, cid, text, "selected.pdf", "text/plain", "synthetic")
            val corpus = object : ProjectCorpus {
                override suspend fun projects() = _l[ProjectRef("project", "assets", "@upload", 1)]
                override suspend fun docs(project: String, prefix: String, glob: String, limit: Int) =
                    _l[ProjectDoc(project, "selected.pdf", cid.value, "1", 1, text.length.toLong(), "application/pdf")]
                override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? = null
                override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText {
                    extracts.incrementAndGet(); return ProjectText(project, id, cid.value, "1", 1, text)
                }
            }
            val runners = _m[
                ConstellationNodes.CURATE j LcncNodeRunner { node, _ ->
                    curates.incrementAndGet()
                    if (policy is CurationIntake.Mode.ReviewRequired) _m["book" j ConstellationNodes.curationBookName(node.params.getValue("book"), ontology),
                        "curationReceiptCid" j cid.value, "ontologyCid" j ontology.value, "reviewStatus" j "unreviewed"]
                    else _m["book" j "selected.pdf"]
                },
                ConstellationNodes.JOIN j LcncNodeRunner { _, _ -> joins.incrementAndGet(); emptyMap() },
            ]
            val intake = CurationIntake(corpus, { runners }, { null }, this, settled = {},
                mode = { _, _ -> policy }, retainedSource = { _, _ -> source })
            try {
                intake.offerSelected("project", "selected.pdf", null); intake.drain()
                val status = intake.status()["project"] as Map<*, *>
                when (policy) {
                    CurationIntake.Mode.Manual -> { assertEquals(1, curates.get()); assertEquals(1, joins.get()); assertEquals(1, status["curated"]) }
                    is CurationIntake.Mode.ReviewRequired -> { assertEquals(1, curates.get()); assertEquals(0, joins.get()); assertEquals(1, status["awaitingReview"]) }
                    is CurationIntake.Mode.Blocked -> { assertEquals(0, curates.get()); assertEquals(0, joins.get()); assertEquals(1, status["failed"]) }
                    else -> error("Unexpected fixture")
                }
                assertEquals(0, extracts.get(), "Retained source or blocked gate must prevent parser extraction")
            } finally { intake.drain() }
        }
    }
}
