package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchReportReactorElement
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.context.Drain
import borg.trikeshed.dag.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.lib.*
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectDbMountTest {

    private class Harness(
        val scopes: ProjectScopes,
        val registry: ProjectDbRegistry,
        val indexLayer: MemoryIndexLayer,
    )

    private fun harness(ledger: File, cas: CasStore = CasStore.inMemory(), files: FileOperations = JvmFileOperations()): Harness {
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val indexLayer = MemoryIndexLayer(MemoryStore(cas, couchStore))
        val bridge = CouchIndexBridge(gateway, indexLayer)
        val registry = ProjectDbRegistry("trikeshed")
        val scopes = ProjectScopes(
            files, gateway, bridge, cas, null,
            projectDbs = registry, ledgerFile = ledger,
            filesRoot = File(ledger.parentFile, "files"),
        )
        return Harness(scopes, registry, indexLayer)
    }

    private fun hierarchy(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), name).apply { mkdirs() }
        File(dir, "report.pdf").writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 1, 2, 3)) // %PDF- head
        File(dir, "notes.txt").writeText("field notes")
        File(dir, "sub").mkdirs()
        File(dir, "sub/deep.pdf").writeBytes(ByteArray(64) { it.toByte() })
        return dir
    }

    @Test
    fun droppedHierarchyBecomesItsOwnDb_servedByTheWire(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-${System.nanoTime()}").apply { mkdirs() }
        val h = harness(File(tmp, "projects.tsv")); val scopes = h.scopes; val registry = h.registry
        val dir = hierarchy("fielddocs${System.nanoTime()}")

        val scope = scopes.mount(dir.absolutePath)
        assertEquals("assets", scope.kind)
        assertEquals(3, scope.paths)
        assertEquals(3, scope.docs, "every file is a doc in the project's OWN db")

        val pdb = assertNotNull(registry.get(scope.name))
        assertEquals(3, pdb.docCount)

        // the wire answers the Couch surface for the new db by first path segment
        val wire = ProjectDbWire(registry)
        val req = "GET /x HTTP/1.1\r\n\r\n".toByteArray()
        val content = wire.route("GET", "/${scope.name}/sub/deep.pdf/content", req, null)
        assertNotNull(content, "project db must serve attachment content")
        assertEquals(200, content.status)
        assertEquals(64, content.bytes?.size)
        assertNull(wire.route("GET", "/nosuchdb/x", req, null), "unknown first segment declines")

        // reserved + duplicate names refused
        assertFailsWith<IllegalArgumentException> { scopes.mount(dir.absolutePath) }
        val apiDir = File(tmp, "api").apply { mkdirs(); File(this, "f.txt").writeText("x") }
        assertFailsWith<IllegalArgumentException> { scopes.mount(apiDir.absolutePath) }

        // the taxonomy index actually populated (through the PROJECT gateway, not the primary)
        assertTrue(
            h.indexLayer.route(borg.trikeshed.memory.IndexKind.RepositoryTaxonomy).entryCount >= 3,
            "project docs must land in the taxonomy index",
        )
    }

    @Test
    fun spaceyFolderNameSanitizesInsteadOfRefusing(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-sp-${System.nanoTime()}").apply { mkdirs() }
        val h = harness(File(tmp, "projects.tsv"))
        val dir = File(tmp, "My PDF Stash").apply { mkdirs(); File(this, "a.pdf").writeBytes(ByteArray(8)) }
        val scope = h.scopes.mount(dir.absolutePath)
        assertEquals("my-pdf-stash", scope.name, "Finder names mount sanitized, not refused")
        assertEquals(1, h.registry.get("my-pdf-stash")?.docCount)
    }

    @Test
    fun indexLayerSurvivesConcurrentReconciles(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val layer = MemoryIndexLayer(MemoryStore(cas, couchStore))
        val failures = java.util.concurrent.atomic.AtomicInteger()
        // Overlapping prefixes ("plane/") force every writer through the SAME route lists —
        // the un-locked ArrayLists NPE'd here (mid-resize null seen by removeAll's iterator).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            (1..6).map { w ->
                launch {
                    for (i in 1..300) {
                        try {
                            val prefix = "plane/w$w/"
                            val entries = (0 until 20).map { k ->
                                borg.trikeshed.memory.CouchIndexEntry(
                                    path = "${prefix}f$i-$k.txt",
                                    hash = borg.trikeshed.job.ContentId.of("$w-$i-$k".encodeToByteArray()),
                                    taxonomy = 1 j { _: Int -> "plane" },
                                    timestamp = i.toLong(),
                                )
                            }
                            layer.replaceRepositoryBatch(prefix, entries.size j { idx: Int -> entries[idx] })
                        } catch (t: Throwable) {
                            failures.incrementAndGet()
                        }
                    }
                }
            }.joinAll()
        }
        assertEquals(0, failures.get(), "concurrent index rebuilds must never corrupt a route")
    }

    @Test
    fun browserUploadLaneBuildsDb_andManifestSurvivesRestart(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-up-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = CasStore.inMemory()  // shared across "boots" — production CAS is file-backed

        val h1 = harness(ledger, cas)
        // the wire decodes Finder names before sanitize: "My%20PDF%20Stash" → my-pdf-stash, never my-20pdf-20stash
        val spacey = ProjectDbWire(h1.registry, uploads = h1.scopes)
            .route("POST", "/_project/My%20PDF%20Stash/begin", "POST /x HTTP/1.1\r\n\r\n".toByteArray(), null)
        assertEquals(200, spacey?.status)
        assertTrue(spacey!!.body.contains("\"my-pdf-stash\""), "encoded spaces must sanitize cleanly: ${spacey.body}")

        val begun = h1.scopes.beginUpload("My Drop")
        assertEquals("my-drop", begun.name)
        h1.scopes.uploadPut("my-drop", "report.pdf", ByteArray(40) { 1 })
        h1.scopes.uploadPut("my-drop", "sub/deep.pdf", ByteArray(64) { 2 })
        assertEquals(2, h1.registry.get("my-drop")?.docCount)
        assertFailsWith<IllegalArgumentException> { h1.scopes.uploadPut("my-drop", "../escape", ByteArray(1)) }

        // wire lane: begin refusal for dup + content served from the uploaded db
        val wire = ProjectDbWire(h1.registry, uploads = h1.scopes)
        val req = "POST /x HTTP/1.1\r\n\r\n".toByteArray()
        val dup = wire.route("POST", "/_project/my-drop/begin", req, null)
        assertEquals(400, dup?.status, "duplicate begin must refuse")
        val put = wire.route(
            "POST", "/_project/my-drop/put?path=via-wire.bin",
            "POST /x HTTP/1.1\r\nContent-Type: application/octet-stream\r\n\r\nRAWBYTES".toByteArray(), null,
        )
        assertEquals(200, put?.status)
        val served = wire.route("GET", "/my-drop/sub/deep.pdf/content", req, null)
        assertEquals(64, served?.bytes?.size)

        // batched puts: framed body = [4B pathLen][path][8B byteLen][bytes]…, one round trip
        fun frame(rel: String, bytes: ByteArray): ByteArray {
            val pb = rel.encodeToByteArray()
            val out = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(out).apply { writeInt(pb.size); write(pb); writeLong(bytes.size.toLong()); write(bytes) }
            return out.toByteArray()
        }
        val batchBody = frame("batch/a.txt", ByteArray(10) { 3 }) + frame("batch/b.txt", ByteArray(20) { 4 })
        val head = "POST /x HTTP/1.1\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
        val batch = wire.route("POST", "/_project/my-drop/putBatch", head + batchBody, null)
        assertEquals(200, batch?.status)
        assertTrue(batch!!.body.contains("\"stored\":2"), "both framed files must land: ${batch.body}")
        assertEquals(20, wire.route("GET", "/my-drop/batch/b.txt/content", req, null)?.bytes?.size)

        // uploads mirror to a browsable files/<name>/ twin (fs dedupes it against CAS)
        assertTrue(File(tmp, "files/my-drop/sub/deep.pdf").isFile, "upload must mirror under files/<name>/")

        // "restart": manifest + CAS rebuild the uploaded db with no source directory anywhere
        val h2 = harness(ledger, cas)
        assertTrue(h2.scopes.remountLedger() >= 1)
        // 2 direct puts + 1 wire put + 2 batched = 5
        assertEquals(5, h2.registry.get("my-drop")?.docCount, "manifest replay must rebuild every uploaded doc")
    }

    private fun durableCas(root: File): CasStore = FileCasStore(JvmFileOperations(), File(root, "cas").absolutePath)

    private fun retainedOntology(cas: CasStore): ContentId =
        CorpusOntology.retain(cas, "law", "test", "(subclass Obligation LegalConcept)", emptyMap()).cid

    @Test
    fun reviewPolicySurvivesRestartAndIsVisibleBeforeRemountAdmission(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = durableCas(tmp)
        val ontology = retainedOntology(cas)
        val review = CurationIntake.Mode.ReviewRequired(ontology)
        val first = harness(ledger, cas)
        val offered = mutableListOf<CurationIntake.Mode>()
        first.scopes.onDocument = { project, _, _ -> offered.add(first.scopes.intakeMode(project)) }
        first.scopes.beginUpload("legal")
        first.scopes.setIntakePolicy("legal", review)
        first.scopes.uploadPut("legal", "contract.txt", "contract".encodeToByteArray())
        assertEquals(listOf<CurationIntake.Mode>(review), offered)
        first.scopes.drain()

        val second = harness(ledger, durableCas(tmp))
        val restored = mutableListOf<CurationIntake.Mode>()
        second.scopes.onDocument = { project, _, _ -> restored.add(second.scopes.intakeMode(project)) }
        try {
            assertEquals(1, second.scopes.remountLedger())
            assertEquals(review, second.scopes.intakeMode("legal"))
            assertEquals(listOf<CurationIntake.Mode>(review), restored, "policy must be loaded before any resume callback")
        } finally {
            second.scopes.drain()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun absentAndWrongKindOntologyCannotReplaceTheEffectivePolicy(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-invalid-${System.nanoTime()}").apply { mkdirs() }
        val cas = durableCas(tmp)
        val h = harness(File(tmp, "projects.tsv"), cas)
        try {
            h.scopes.beginUpload("legal")
            val review = CurationIntake.Mode.ReviewRequired(retainedOntology(cas))
            h.scopes.setIntakePolicy("legal", review)
            val absent = ContentId.of("not retained".encodeToByteArray())
            val wrongKind = cas.put("not an ontology".encodeToByteArray())
            for (cid in listOf(absent, wrongKind)) {
                assertFailsWith<Exception> { h.scopes.setIntakePolicy("legal", CurationIntake.Mode.ReviewRequired(cid)) }
                assertEquals(review, h.scopes.intakeMode("legal"), "an invalid update must not downgrade the mode")
            }
        } finally {
            h.scopes.drain()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun unreadableReviewPolicyBlocksResumedAdmissionsRatherThanFallingBackToLegacy(): Unit = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "pdb-policy-cas-${System.nanoTime()}").apply { mkdirs() }
        val cas = durableCas(root)
        val wrongKind = cas.put("not an ontology".encodeToByteArray())
        val absent = ContentId.of("not retained".encodeToByteArray())
        val cases = listOf(
            "future-mode" to "",
            "review-required" to "",
            "review-required" to "not-a-cid",
            "review-required" to absent.value,
            "review-required" to wrongKind.value,
        )
        for ((modeName, cid) in cases) {
            val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-bad-${System.nanoTime()}").apply { mkdirs() }
            val ledger = File(tmp, "projects.tsv")
            val first = harness(ledger, cas)
            first.scopes.beginUpload("legal")
            first.scopes.uploadPut("legal", "contract.txt", "contract".encodeToByteArray())
            first.scopes.drain()
            val columns = ledger.readLines().single().split('\t').take(3)
            ledger.writeText(columns.joinToString("\t") + "\t\t$modeName\t$cid\n")
            val second = harness(ledger, cas)
            val restored = mutableListOf<CurationIntake.Mode>()
            second.scopes.onDocument = { project, _, _ -> restored.add(second.scopes.intakeMode(project)) }
            try {
                assertEquals(1, second.scopes.remountLedger())
                assertTrue(second.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked)
                assertEquals(1, restored.size)
                assertTrue(restored.single() is CurationIntake.Mode.Blocked,
                    "the resume callback must never observe legacy for unreadable explicit policy")
            } finally {
                second.scopes.drain()
                tmp.deleteRecursively()
            }
        }
        root.deleteRecursively()
    }

    @Test
    fun failedPolicyPersistenceBlocksAdmissionsUntilTheOutcomeIsResolved(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-write-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = durableCas(tmp)
        val h = harness(ledger, cas)
        try {
            h.scopes.beginUpload("legal")
            val review = CurationIntake.Mode.ReviewRequired(retainedOntology(cas))
            h.scopes.setIntakePolicy("legal", review)
            assertTrue(ledger.delete())
            assertTrue(ledger.mkdir(), "replace the ledger file with a directory to force persistence failure")
            assertFailsWith<Exception> { h.scopes.setIntakePolicy("legal", CurationIntake.Mode.Legacy) }
            assertTrue(h.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked,
                "a failed atomic write can have an uncertain durable outcome")
        } finally {
            h.scopes.drain()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun prePolicyLedgerRowsRestoreLegacyMode(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-legacy-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = durableCas(tmp)
        val first = harness(ledger, cas)
        first.scopes.beginUpload("legal", decisionProvider = DecisionProvider.JEV)
        first.scopes.uploadPut("legal", "contract.txt", "contract".encodeToByteArray())
        first.scopes.drain()
        val columns = ledger.readLines().single().split('\t').take(4)
        ledger.writeText(columns.joinToString("\t") + "\n")
        val second = harness(ledger, cas)
        val restored = mutableListOf<CurationIntake.Mode>()
        second.scopes.onDocument = { project, _, _ -> restored.add(second.scopes.intakeMode(project)) }
        try {
            assertEquals(1, second.scopes.remountLedger())
            assertEquals(CurationIntake.Mode.Legacy, second.scopes.intakeMode("legal"))
            assertEquals(listOf<CurationIntake.Mode>(CurationIntake.Mode.Legacy), restored)
            assertEquals(DecisionProvider.JEV, second.scopes.list().single().decisionProvider)
        } finally {
            second.scopes.drain()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun volatileCasCannotClaimDurableReviewPolicy(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-volatile-${System.nanoTime()}").apply { mkdirs() }
        val cas = CasStore.inMemory()
        val h = harness(File(tmp, "projects.tsv"), cas)
        try {
            h.scopes.beginUpload("legal")
            val review = CurationIntake.Mode.ReviewRequired(retainedOntology(cas))
            assertFailsWith<Exception> { h.scopes.setIntakePolicy("legal", review) }
            assertEquals(CurationIntake.Mode.Legacy, h.scopes.intakeMode("legal"))
        } finally {
            h.scopes.drain()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun invalidGuardOrLedgerNeverRestoresLegacy(): Unit = runBlocking {
        for (damage in listOf("truncated", "missing", "pending")) {
            val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-tamper-${System.nanoTime()}").apply { mkdirs() }
            val ledger = File(tmp, "projects.tsv")
            val cas = durableCas(tmp)
            val first = harness(ledger, cas)
            first.scopes.beginUpload("legal")
            first.scopes.setIntakePolicy("legal", CurationIntake.Mode.ReviewRequired(retainedOntology(cas)))
            first.scopes.uploadPut("legal", "contract.txt", "contract".encodeToByteArray())
            first.scopes.drain()
            val original = ledger.readLines().single()
            when (damage) {
                "missing" -> ledger.writeText("")
                "truncated" -> ledger.writeText(original.split('\t').take(4).joinToString("\t") + "\n")
                "pending" -> File(tmp, "project-intake-policy/legal.guard").writeText("pending\n")
            }
            val second = harness(ledger, durableCas(tmp))
            val admittedModes = mutableListOf<CurationIntake.Mode>()
            second.scopes.onDocument = { project, _, _ -> admittedModes.add(second.scopes.intakeMode(project)) }
            try {
                second.scopes.remountLedger()
                assertTrue(second.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked,
                    "the retained guard must detect $damage")
                assertTrue(admittedModes.all { it is CurationIntake.Mode.Blocked },
                    "no resume callback may see a legacy mode after policy tampering")
                if (damage == "missing") assertTrue(admittedModes.isEmpty())
            } finally {
                second.scopes.drain()
                tmp.deleteRecursively()
            }
        }
    }

    @Test
    fun explicitReviewLedgerWithoutGuardBlocksBeforeResumeHooks(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-no-guard-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = durableCas(tmp)
        val first = harness(ledger, cas)
        first.scopes.beginUpload("legal")
        first.scopes.uploadPut("legal", "contract.txt", "contract".encodeToByteArray())
        val ontology = retainedOntology(cas)
        first.scopes.drain()
        // No setIntakePolicy call means no review guard was committed.
        val columns = ledger.readLines().single().split('\t').take(3)
        ledger.writeText(columns.joinToString("\t") + "\t\treview-required\t${ontology.value}\n")
        val second = harness(ledger, durableCas(tmp))
        val admittedModes = mutableListOf<CurationIntake.Mode>()
        second.scopes.onDocument = { project, _, _ -> admittedModes.add(second.scopes.intakeMode(project)) }
        try {
            second.scopes.remountLedger()
            assertTrue(second.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked)
            assertTrue(admittedModes.all { it is CurationIntake.Mode.Blocked },
                "an explicit review row without its guard cannot be trusted")
        } finally {
            second.scopes.drain()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun unmountedReviewProjectNameStaysBlockedOnRecreationAndRestart(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-recreate-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = durableCas(tmp)
        val first = harness(ledger, cas)
        val calls = mutableListOf<String>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, _ -> calls.add("curate"); emptyMap() },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> calls.add("join"); emptyMap() },
        )
        fun attach(h: Harness): CurationIntake {
            val intake = CurationIntake(JvmProjectCorpus(h.registry, h.scopes), { runners }, { null }, this,
                mode = { project, _ -> h.scopes.intakeMode(project) })
            h.scopes.onDocument = { project, id, provider -> intake.offer(project, id, provider) }
            return intake
        }
        try {
            first.scopes.beginUpload("legal")
            first.scopes.setIntakePolicy("legal", CurationIntake.Mode.ReviewRequired(retainedOntology(cas)))
            assertTrue(first.scopes.unmount("legal"))
            first.scopes.beginUpload("legal")
            assertTrue(first.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked)
            val intake = attach(first)
            first.scopes.uploadPut("legal", "new.html", "<p>new document</p>".encodeToByteArray())
            intake.drain()
            assertTrue(calls.isEmpty(), "recreation must not publish under a silently reset legacy policy")
            assertEquals(1, (intake.status().getValue("legal") as Map<*, *>)["failed"])
            first.scopes.drain()

            val second = harness(ledger, durableCas(tmp))
            val restoredIntake = attach(second)
            try {
                assertEquals(1, second.scopes.remountLedger())
                assertTrue(second.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked)
                restoredIntake.drain()
                assertTrue(calls.isEmpty(), "restart must not join the recreated project either")
                assertEquals(1, (restoredIntake.status().getValue("legal") as Map<*, *>)["failed"])
            } finally {
                restoredIntake.drain()
                second.scopes.drain()
            }
        } finally {
            first.scopes.drain()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun failedUnmountLedgerWriteRetainsScopeAndBlocksGuardedPolicy(): Unit = runBlocking {
        for (failAfterPublication in listOf(false, true)) {
            val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-policy-unmount-failure-${System.nanoTime()}").apply { mkdirs() }
            val ledger = File(tmp, "projects.tsv")
            val backing = JvmFileOperations()
            var failLedgerWrite = false
            val failing = object : FileOperations by backing {
                override fun writeAtomically(filename: String, bytes: ByteArray) {
                    val fail = failLedgerWrite && File(filename).absoluteFile == ledger.absoluteFile
                    if (fail && !failAfterPublication) error("injected ledger failure before publication")
                    backing.writeAtomically(filename, bytes)
                    if (fail) error("injected ledger failure after publication")
                }
            }
            val cas = durableCas(tmp)
            val h = harness(ledger, cas, failing)
            try {
                h.scopes.beginUpload("legal")
                h.scopes.setIntakePolicy("legal", CurationIntake.Mode.ReviewRequired(retainedOntology(cas)))
                h.scopes.uploadPut("legal", "contract.txt", "contract".encodeToByteArray())
                failLedgerWrite = true
                assertFailsWith<Exception> { h.scopes.unmount("legal") }
                assertTrue(h.scopes.list().any { it.name == "legal" }, "the live scope must survive failed unmount")
                assertNotNull(h.registry.get("legal"), "the project database must survive failed unmount")
                assertTrue(h.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked,
                    "the policy must block while ledger publication is uncertain")
            } finally {
                h.scopes.drain()
                tmp.deleteRecursively()
            }
        }
    }

    @Test
    fun failedRegistryOwnerDrainRetainsEvidenceAndRequiresRecovery(): Unit = runBlocking {
        for (failure in listOf("none", "restore", "recoveryGuard", "cancellation")) {
            val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-owner-failure-${System.nanoTime()}").apply { mkdirs() }
            val ledger = File(tmp, "projects.tsv")
            val backing = JvmFileOperations()
            var injectFailure = false
            val files = object : FileOperations by backing {
                override fun writeAtomically(filename: String, bytes: ByteArray) {
                    if (injectFailure && failure == "restore" && File(filename).absoluteFile == ledger.absoluteFile && bytes.isNotEmpty())
                        error("injected ledger restore failure")
                    if (injectFailure && failure == "recoveryGuard" && filename.endsWith("/legal.guard") &&
                        bytes.decodeToString().trim() == "recovery") error("injected recovery guard failure")
                    backing.writeAtomically(filename, bytes)
                }
            }
            val cas = durableCas(tmp)
            val h = harness(ledger, cas, files)
            try {
                h.scopes.beginUpload("legal")
                h.scopes.setIntakePolicy("legal", CurationIntake.Mode.ReviewRequired(retainedOntology(cas)))
                val originalBytes = "contract".encodeToByteArray()
                val sourceCid = h.scopes.uploadPut("legal", "contract.txt", originalBytes)
                val ledgerBefore = ledger.readBytes()
                val manifest = File(tmp, "project-manifests/legal.tsv")
                val manifestBefore = manifest.readBytes()
                val source = File(tmp, "files/legal/contract.txt")
                val guard = File(tmp, "project-intake-policy/legal.guard")
                assertTrue(guard.isFile)
                assertTrue(source.isFile)
                val ownerField = ProjectDbRegistry::class.java.getDeclaredField("owners").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val owners = ownerField.get(h.registry) as MutableMap<String, Join<Job, Drain>>
                val finished = Job().apply { complete() }
                owners["legal"] = finished j Drain {
                    if (failure == "cancellation") throw kotlinx.coroutines.CancellationException("injected cancellation")
                    error("injected owner drain failure")
                }
                injectFailure = true
                if (failure == "cancellation") assertFailsWith<kotlinx.coroutines.CancellationException> { h.scopes.unmount("legal") }
                else assertFailsWith<Exception> { h.scopes.unmount("legal") }
                assertNull(h.registry.get("legal"), "registry removed the DB before the owner drain failed")
                assertTrue(h.scopes.list().any { it.name == "legal" }, "recovery scope metadata must remain")
                assertTrue(h.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked)
                assertTrue(guard.isFile, "the guard must survive a partial unmount")
                if (failure == "none" || failure == "cancellation")
                    assertTrue(ledger.readBytes().contentEquals(ledgerBefore), "restore the exact ledger row")
                if (failure == "recoveryGuard") assertTrue(!ledger.exists() || ledger.readText().isEmpty(),
                    "do not restore a row without first durably recording recovery")
                assertTrue(manifest.readBytes().contentEquals(manifestBefore))
                assertTrue(source.readBytes().contentEquals(originalBytes))
                assertTrue(cas.get(sourceCid)?.contentEquals(originalBytes) == true)
                assertFailsWith<Exception> { h.scopes.setIntakePolicy("legal", CurationIntake.Mode.Legacy) }
                assertFailsWith<Exception> { h.scopes.unmount("legal") }
                assertTrue(manifest.isFile && source.isFile, "automatic retry must not clean up evidence")
                h.scopes.drain()
                val restarted = harness(ledger, durableCas(tmp))
                val resumedModes = mutableListOf<CurationIntake.Mode>()
                restarted.scopes.onDocument = { project, _, _ -> resumedModes.add(restarted.scopes.intakeMode(project)) }
                try {
                    restarted.scopes.remountLedger()
                    assertTrue(restarted.scopes.intakeMode("legal") is CurationIntake.Mode.Blocked,
                        "partial unmount recovery must remain blocked after restart")
                    assertTrue(resumedModes.all { it is CurationIntake.Mode.Blocked },
                        "no resume hook may observe a usable policy while recovery is unresolved")
                } finally {
                    restarted.scopes.drain()
                }
            } finally {
                h.scopes.drain()
                tmp.deleteRecursively()
            }
        }
    }

    @Test
    fun uploadProviderAndDocumentOverrideSurviveRestartAndInvalidProviderWritesNothing(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-provider-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = CasStore.inMemory()
        val first = harness(ledger, cas)
        val wire = ProjectDbWire(first.registry, first.scopes)
        val admissions = mutableListOf<Pair<String, DecisionProvider?>>()
        first.scopes.onDocument = { _, id, provider -> admissions.add(id to provider) }
        val request = "POST /x HTTP/1.1\r\n\r\n".encodeToByteArray()
        assertEquals(400, wire.route("POST", "/_project/invalid/begin?decisionProvider=unknown", request, null)?.status)
        assertNull(first.registry.get("invalid"))
        assertEquals(200, wire.route("POST", "/_project/legal/begin?decisionProvider=jev", request, null)?.status)
        assertEquals(200, wire.route("POST", "/_project/legal/put?path=a.txt", request + "alpha".encodeToByteArray(), null)?.status)
        assertEquals(200, wire.route("POST", "/_project/legal/put?path=b.txt&decisionProvider=${DecisionProvider.OPENAI.id}", request + "beta".encodeToByteArray(), null)?.status)
        assertEquals(400, wire.route("POST", "/_project/legal/put?path=bad.txt&decisionProvider=unknown", request + "bad".encodeToByteArray(), null)?.status)
        assertNull(first.registry.get("legal")?.gateway?.getAttachmentRef("bad.txt"))
        assertEquals(listOf<Pair<String, DecisionProvider?>>("a.txt" to DecisionProvider.JEV, "b.txt" to DecisionProvider.OPENAI), admissions)
        first.scopes.drain()

        val second = harness(ledger, cas)
        val resumed = mutableListOf<Pair<String, DecisionProvider?>>()
        second.scopes.onDocument = { _, id, provider -> resumed.add(id to provider) }
        assertEquals(1, second.scopes.remountLedger())
        assertEquals(DecisionProvider.JEV, second.scopes.list().single().decisionProvider)
        assertEquals(DecisionProvider.JEV, second.scopes.decisionProvider("legal", "a.txt"))
        assertEquals(DecisionProvider.OPENAI, second.scopes.decisionProvider("legal", "b.txt"))
        assertEquals(admissions, resumed)
        second.scopes.drain()
    }

    @Test
    fun mountClonesIntoForgeHome_originalCanVanish(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-cl-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val cas = CasStore.inMemory()
        val dir = hierarchy("ephemeral${System.nanoTime()}")

        val h1 = harness(ledger, cas)
        val scope = h1.scopes.mount(dir.absolutePath)
        // the durable source is the CLONE in the forge home, not the drop origin
        assertTrue(scope.path.startsWith(File(tmp, "files").absolutePath), "scope path must be the forge-home clone: ${scope.path}")
        assertTrue(File(tmp, "files/${scope.name}/sub/deep.pdf").isFile, "clone must carry the hierarchy")

        // the original folder vanishes — the project must not care
        dir.deleteRecursively()
        val h2 = harness(ledger, cas)
        assertEquals(1, h2.scopes.remountLedger(), "remount must rebuild from the clone after the original is gone")
        assertEquals(3, h2.registry.get(scope.name)?.docCount)
    }

    @Test
    fun ledgerRemountSurvivesRestart(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-led-${System.nanoTime()}").apply { mkdirs() }
        val ledger = File(tmp, "projects.tsv")
        val dir = hierarchy("survivor${System.nanoTime()}")

        val h1 = harness(ledger); val scopes1 = h1.scopes; val registry1 = h1.registry
        val name = scopes1.mount(dir.absolutePath).name
        assertEquals(3, registry1.get(name)?.docCount)
        // the ledger records the durable forge-home CLONE, not the drop origin
        assertTrue(ledger.readText().contains("files/$name"), "ledger must point at the clone: ${ledger.readText()}")

        // "restart": fresh registry + scopes over the SAME ledger re-creates the db
        val h2 = harness(ledger); val scopes2 = h2.scopes; val registry2 = h2.registry
        assertEquals(1, scopes2.remountLedger())
        assertEquals(3, registry2.get(name)?.docCount, "ledger replay rebuilds the project db")
        // idempotent: replaying again mounts nothing new
        assertEquals(0, scopes2.remountLedger())
    }

    @Test fun unmountReleasesReaderFactsAndKifBeforeRemount(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-owner-${System.nanoTime()}").apply { mkdirs() }
        val h = harness(File(tmp, "projects.tsv"))
        val rete = ReteNetwork()
        val bank = KifKnowledgeBase()
        val (tee, subscription) = KifTee.attach(rete, bank)
        rete.assert(FactId("outside", "retained"), mapOf("kind" to "independent"),
            ContentId.of("outside".encodeToByteArray()), BlackboardContext("outside"))
        val baseline = bank.size()
        h.registry.factPlane(this, rete)
        try {
            h.scopes.beginUpload("owned")
            val retired = assertNotNull(h.registry.get("owned"))
            val first = h.scopes.uploadPut("owned", "a.txt", "first".encodeToByteArray())
            val latest = h.scopes.uploadPut("owned", "a.txt", "latest".encodeToByteArray())
            h.scopes.uploadPut("owned", "b.txt", "second document".encodeToByteArray())
            withTimeout(5_000) {
                while (rete.snapshot(s_["owned"]).view.none { it.factId.b == "a.txt" && it.fields["contentId"] == latest.value } ||
                    rete.snapshot(s_["owned"]).size != 2) delay(1)
            }
            assertEquals(3, tee.trackedCount(), "Revisions update facts; independent documents remain distinct")
            assertTrue(first.value !in bank.toKifFile(), "The prior revision's fields have left KIF")
            assertTrue(h.scopes.unmount("owned"))
            assertNull(h.registry.get("owned"))
            assertEquals(0, rete.snapshot(s_["owned"]).size)
            assertEquals(1, tee.trackedCount())
            assertEquals(baseline, bank.size(), "Unmount retracts only the removed database's projection")
            assertEquals(0, coroutineContext[Job]!!.children.count(), "The mounted reader's supervisor must be finished")
            retired.db.put("ghost", mapOf("kind" to "retired store"), null)
            delay(20)
            assertEquals(0, rete.snapshot(s_["owned"]).size, "The retired store no longer has a reader subscription")
            h.scopes.beginUpload("owned")
            val replacement = h.scopes.uploadPut("owned", "a.txt", "remounted".encodeToByteArray())
            withTimeout(5_000) {
                while (rete.snapshot(s_["owned"]).view.none { it.fields["contentId"] == replacement.value }) delay(1)
            }
            assertEquals(1, rete.snapshot(s_["owned"]).size)
            assertEquals(2, tee.trackedCount())
        } finally {
            h.scopes.drain()
            subscription.close()
            tmp.deleteRecursively()
        }
    }

    @Test fun immediateUnmountFinishesAdmittedFrames(): Unit = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pdb-drain-${System.nanoTime()}").apply { mkdirs() }
        val h = harness(File(tmp, "projects.tsv"))
        val rete = ReteNetwork()
        val bank = KifKnowledgeBase()
        val (tee, subscription) = KifTee.attach(rete, bank)
        val report = CouchReportReactorElement()
        h.registry.factPlane(this, rete, report)
        try {
            h.scopes.beginUpload("drain")
            repeat(100) { h.scopes.uploadPut("drain", "document-$it.txt", "payload-$it".encodeToByteArray()) }
            assertTrue(h.scopes.unmount("drain"))
            assertEquals(100L, report.reportState.value.commits, "Every accepted frame completes before removal")
            assertEquals(0, rete.snapshot(s_["drain"]).size)
            assertEquals(0, tee.trackedCount())
            assertEquals(0, bank.size())
            assertEquals(0, coroutineContext[Job]!!.children.count())
        } finally {
            h.scopes.drain()
            subscription.close()
            tmp.deleteRecursively()
        }
    }
}
