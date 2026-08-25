package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.j
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
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

    private fun harness(ledger: File, cas: CasStore = CasStore.inMemory()): Harness {
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val indexLayer = MemoryIndexLayer(MemoryStore(cas, couchStore))
        val bridge = CouchIndexBridge(gateway, indexLayer)
        val registry = ProjectDbRegistry("trikeshed")
        val scopes = ProjectScopes(
            JvmFileOperations(), gateway, bridge, cas, null,
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

        // uploads mirror to a browsable files/<name>/ twin (fs dedupes it against CAS)
        assertTrue(File(tmp, "files/my-drop/sub/deep.pdf").isFile, "upload must mirror under files/<name>/")

        // "restart": manifest + CAS rebuild the uploaded db with no source directory anywhere
        val h2 = harness(ledger, cas)
        assertTrue(h2.scopes.remountLedger() >= 1)
        assertEquals(3, h2.registry.get("my-drop")?.docCount, "manifest replay must rebuild every uploaded doc")
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
}
