package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.couch.isam.ConfixIsamElement
import borg.trikeshed.daemon.OroborosDaemon
import borg.trikeshed.dag.*
import borg.trikeshed.docs.DocumentSurface
import borg.trikeshed.forge.server.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.isam.ZstdDictionaryCodec
import borg.trikeshed.job.*
import borg.trikeshed.kanban.module.WorkspaceService
import borg.trikeshed.lcnc.ccek.ccekReactorBinding
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.util.oroboros.*
import borg.trikeshed.agent.keymux.KeyMux
import kotlinx.coroutines.*
import borg.trikeshed.agent.modelmux.ModelMux
import java.nio.file.Path
import kotlin.test.*

class DenseCouchWorkspaceTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path
    private val files = JvmFileOperations()

    private fun open(scope: CoroutineScope): CouchStorage = OroborosDaemon.openCouchStorage(
        FileCasStore(files, root.resolve("cas").toString()), files,
        borg.trikeshed.common.File(root.toString()), scope, CouchStorageFormat.CONFIX_ISAM)

    private fun context(scope: CoroutineScope, owner: CouchStorage, inspection: ConfixIsamElement): ModuleContext =
        ModuleContext(owner.couch, ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), owner.couch.cas, CouchAttachmentGateway(owner.couch.store, owner.couch.cas),
            ModuleRouteRegistry(), scope, { 123L }, root.toFile(), confixIsam = inspection)

    private fun inspection(scope: CoroutineScope, owner: CouchStorage) =
        ConfixIsamElement(scope, owner.couch.store.changes, root.resolve("confix/couch").toString(), files,
            owner.couch.cas, dictionaryCodec = { ZstdDictionaryCodec(it) }, primary = owner)

    private suspend fun request(service: WorkspaceService, method: String, value: Map<String, Any?> = emptyMap()): Map<*, *> {
        val response = service.route(method, jsonOf(value))
        assertEquals(200, response.status, response.body)
        return assertIs<Map<*, *>>(reify(response.body))
    }

    @Test fun primaryInspectionRetainsExplicitDictionaryBackedFileTreeFacets() = runBlocking<Unit> {
        lateinit var revision: String
        val id = borg.trikeshed.btrfs.VmWorldTeleport.PREFIX + "review"
        open(this).use { owner ->
            val inspection = inspection(this, owner)
            try {
                val world = borg.trikeshed.btrfs.BtrfsWorldStore.ofMemory(owner.couch.cas)
                val mount = world.mount("review")
                assertTrue(mount.createSubvolume("review"))
                assertTrue(mount.writeFile("review", "case/医.txt", "synthetic evidence".encodeToByteArray()))
                assertEquals(true, borg.trikeshed.btrfs.VmWorldTeleport(owner.couch, world).publish("review")["ok"])
                revision = owner.couch.docJson(id)!!["_rev"] as String
                val dictionary = owner.couch.blockPut("synthetic review case file metadata evidence".encodeToByteArray())
                val service = WorkspaceService(context(this, owner, inspection))
                val source = mapOf("kind" to "couch", "id" to id, "rev" to revision)
                assertNotNull(request(service, "POST", mapOf("source" to source))["fileTree"])
                val sequence = owner.couch.updateSeq
                val result = request(service, "PUT", mapOf("action" to "index-file-tree", "source" to source,
                    "dictionaryCid" to dictionary.value))
                assertEquals(1, (result["imported"] as Number).toInt())
                assertEquals(dictionary.value, (result["compression"] as Map<*, *>)["dictionaryCid"])
                assertEquals(sequence, owner.couch.updateSeq)
                assertFalse(files.exists(root.resolve("confix/couch.store").toString()))
            } finally { inspection.drain() }
        }
        open(this).use { owner ->
            val inspection = inspection(this, owner)
            try {
                val tree = assertNotNull(inspection.fileTree(id, revision, read = true))
                assertEquals(true, tree.a["indexed"])
                assertNotNull(tree.b)
                assertFailsWith<IllegalArgumentException> {
                    ConfixIsamElement(this, CouchStoreFactory.casBacked(owner.couch.cas).changes,
                        root.resolve("unrelated").toString(), files, owner.couch.cas, primary = owner)
                }
            } finally { inspection.drain() }
        }
    }

    @org.junit.jupiter.api.Timeout(value = 11, unit = java.util.concurrent.TimeUnit.MINUTES)
    @Test fun daemonStorageFeedsAttachmentsDocumentsJobLogsAndFacetsWithoutASecondProjection() = runBlocking<Unit> {
        val bytes = "# Synthetic review\n\nRetained quotation 医; requires human review.".encodeToByteArray()
        val cid = ContentId.of(bytes)
        val payload = "retained synthetic council command".encodeToByteArray()
        lateinit var revision: String
        open(this).use { owner ->
            val inspection = inspection(this, owner)
            try {
                val ctx = context(this, owner, inspection)
                ctx.attachments.putAttachment(OroborosAttachmentRef("case.md", "text/markdown", bytes.size.toLong(),
                    cid, "fixture", "original", 0), bytes)
                revision = owner.couch.docJson("case.md")!!["_rev"] as String
                CouchJobLog.open(owner.couch, "council-log/case").append(1L, payload)
                owner.couch.localPut("peer", mapOf("last_seq" to owner.couch.updateSeq))
                val service = WorkspaceService(ctx)
                val catalog = request(service, "GET")["storage"] as Map<*, *>
                assertEquals("primary", catalog["role"])
                assertEquals(false, catalog["importAvailable"])
                assertEquals(2, (catalog["indexedRevisions"] as Number).toInt())
                val query = mapOf("source" to mapOf("kind" to "couch", "id" to "case.md", "rev" to revision),
                    "path" to listOf("contentId"))
                val indexed = request(service, "POST", query)
                assertEquals(cid.value, (indexed["confix"] as Map<*, *>)["value"])
                val headPath = root.resolve("couch.store/HEAD").toString()
                val head = files.readAllBytes(headPath)
                assertEquals(400, service.route("PUT", jsonOf(mapOf("action" to "index-couch"))).status)
                assertContentEquals(head, files.readAllBytes(headPath))
                assertFalse(files.exists(root.resolve("confix/couch.store").toString()))

                // The actual document HTTP consumer and commonMain renderer use the same Couch.
                val registry = ProjectDbRegistry("primary")
                registry.register(ProjectDb("review", root.toString(), "assets", owner.couch, owner.couch.store,
                    ctx.attachments, CouchWireRouter(owner.couch, "")))
                val memory = MemoryIndexLayer(MemoryStore(owner.couch.cas, owner.couch.store))
                val scopes = ProjectScopes(files, ctx.attachments, CouchIndexBridge(ctx.attachments, memory), owner.couch.cas,
                    null, projectDbs = registry)
                try {
                    val keys = KeyMux {}
                    val wire = PatchWire(BrainClient(apiKey = "fixture", keyMux = keys, modelMux = ModelMux(keys) {}),
                        scopes, attachments = ctx.attachments, corpus = JvmProjectCorpus(registry, scopes))
                    val response = assertNotNull(wire.route("GET", "/api/projects/review/docs/case.md", "", null))
                    assertEquals(200, response.status, response.body)
                    val view = assertNotNull(DocumentSurface.document(reify(response.body)))
                    assertEquals(bytes.decodeToString(), view.text)
                    assertEquals(cid.value, view.cid)
                    assertEquals(revision, view.rev)
                    assertContains(DocumentSurface.documentHtml(view), "Retained quotation")
                } finally { scopes.drain(); registry.drain(); memory.close() }

                if (SystemOperations.default.getenv("LCNC_DENSE_PRIMARY_UI") == "1") {
                    val before = owner.couch.updateSeq
                    val binding = ccekReactorBinding(coroutineContext)
                    binding.reactor.open()
                    try {
                        retainedSimulationBrowser(ctx, null, binding,
                            LcncProgram("dense-storage", emptySeriesOf(), emptySeriesOf()), stateDirectory = root.toFile(), observations = {
                            mapOf("storage" to "primary", "sequence" to owner.couch.updateSeq, "caseRevision" to revision)
                        })
                    } finally { withContext(NonCancellable) { binding.reactor.drain() } }
                    assertEquals(before, owner.couch.updateSeq, "Inspecting primary storage must not mutate Couch")
                }
                inspection.drain()
                assertEquals(true, owner.couch.put("after-inspection", mapOf("v" to "retained"), null)["ok"],
                    "Draining a borrowed inspection lane must not close the primary writer")
            } finally { inspection.drain() }
        }
        open(this).use { owner ->
            val inspection = inspection(this, owner)
            try {
                val ctx = context(this, owner, inspection)
                assertContentEquals(bytes, assertNotNull(ctx.attachments.getAttachment("case.md")).second)
                assertEquals(revision, owner.couch.docJson("case.md")!!["_rev"])
                assertContentEquals(payload, CouchJobLog.open(owner.couch, "council-log/case").replay().single().payload)
                assertNotNull(owner.couch.localGet("peer"))
                assertEquals("retained", owner.couch.docJson("after-inspection")!!["v"])
                assertNotNull(inspection.document("case.md", revision))
            } finally { inspection.drain() }
        }
    }
}
