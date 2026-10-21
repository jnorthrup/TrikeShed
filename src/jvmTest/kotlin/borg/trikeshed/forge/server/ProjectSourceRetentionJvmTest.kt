package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.PackCasStore
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

/** Real disk-backed CAS, not an in-memory substitute for file-reference lifetime. */
class ProjectSourceRetentionJvmTest {
    private class Rig {
        val root = File(System.getProperty("java.io.tmpdir"), "source-retention-${System.nanoTime()}").apply { mkdirs() }
        val files = JvmFileOperations()
        lateinit var cas: PackCasStore
        lateinit var registry: ProjectDbRegistry
        lateinit var scopes: ProjectScopes
        private var closed = true
        init { open() }
        private fun open() {
            cas = PackCasStore(files, File(root, "cas").absolutePath)
            val store = CouchStoreFactory.casBacked(cas)
            val gateway = CouchAttachmentGateway(store, cas)
            registry = ProjectDbRegistry("trikeshed")
            scopes = ProjectScopes(files, gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))),
                cas, null, projectDbs = registry, ledgerFile = File(root, "mount-ledger.tsv"), filesRoot = File(root, "files"))
            closed = false
        }
        suspend fun close() { if (!closed) { scopes.drain(); cas.close(); closed = true } }
        suspend fun restart() { close(); open(); scopes.remountLedger() }
        fun mirror(project: String, id: String) = File(File(File(root, "files"), project), id)
        fun manifest(project: String) = File(root, "project-manifests/$project.tsv")
    }

    private suspend fun fixture(block: suspend (Rig) -> Unit) {
        val r = Rig()
        try { block(r) } finally { r.close(); r.root.deleteRecursively() }
    }

    @Test fun replacedUploadKeepsOldBytesAcrossRestart(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("history")
        val a = "synthetic original audit bytes".encodeToByteArray()
        val b = "synthetic updated audit bytes with a new length".encodeToByteArray()
        val first = r.scopes.uploadPut(scope.name, "source.bin", a)
        val second = r.scopes.uploadPut(scope.name, "source.bin", b)
        assertContentEquals(a, r.cas.get(first), "the prior CID must still serve its original bytes")
        assertContentEquals(b, r.cas.get(second))
        r.restart()
        assertContentEquals(a, r.cas.get(first))
        assertContentEquals(b, r.cas.get(second))
        assertEquals(second, r.registry.get(scope.name)!!.gateway.getAttachmentRef("source.bin")!!.contentId)
    } }

    @Test fun logicalDeletionKeepsContentAddressedHistory(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("deleted-history")
        val bytes = "synthetic deleted source".encodeToByteArray()
        val cid = r.scopes.uploadPut(scope.name, "source.bin", bytes)
        r.scopes.uploadRemove(scope.name, "source.bin")
        assertTrue(r.registry.get(scope.name)!!.store.head.isDeleted("source.bin"))
        assertContentEquals(bytes, r.cas.get(cid), "logical removal is not deletion of the promised CAS history")
        r.restart()
        assertContentEquals(bytes, r.cas.get(cid))
    } }

    @Test fun unmountKeepsContentAddressedHistory(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("unmounted-history")
        val bytes = "synthetic unmounted source".encodeToByteArray()
        val cid = r.scopes.uploadPut(scope.name, "source.bin", bytes)
        assertTrue(r.scopes.unmount(scope.name))
        assertNull(r.registry.get(scope.name))
        assertContentEquals(bytes, r.cas.get(cid))
        r.restart()
        assertContentEquals(bytes, r.cas.get(cid))
    } }

    @Test fun concurrentReplacementsRetainEveryAcceptedVersion(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("concurrent-history")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val bodies = List(8) { "synthetic concurrent version $it with payload ${"x".repeat(it + 1)}".encodeToByteArray() }
            val futures = bodies.map { bytes -> pool.submit<ContentId> { start.await(); r.scopes.uploadPut(scope.name, "source.bin", bytes) } }
            start.countDown()
            val ids = futures.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            for (i in ids.indices) assertContentEquals(bodies[i], r.cas.get(ids[i]))
        } finally { start.countDown(); pool.shutdownNow() }
    } }

    @Test fun missingPriorBytesRefuseReplacementBeforeMutation(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("missing-prior")
        val cid = r.scopes.uploadPut(scope.name, "source.bin", "original missing fixture".encodeToByteArray())
        assertTrue(r.mirror(scope.name, "source.bin").delete())
        assertFailsWith<IllegalStateException> { r.scopes.uploadPut(scope.name, "source.bin", "new replacement".encodeToByteArray()) }
        assertEquals(cid, r.registry.get(scope.name)!!.gateway.getAttachmentRef("source.bin")!!.contentId)
        assertFalse(r.mirror(scope.name, "source.bin").exists())
    } }

    @Test fun changedFileStampCanBeRecoveredWhenBytesStillMatch(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("stamp-change")
        val a = "same source bytes with a changed stamp".encodeToByteArray()
        val cid = r.scopes.uploadPut(scope.name, "source.bin", a)
        val file = r.mirror(scope.name, "source.bin")
        assertTrue(file.setLastModified(file.lastModified() + 10_000))
        assertNull(r.cas.get(cid), "the reference stamp really became invalid")
        r.scopes.uploadPut(scope.name, "source.bin", "replacement version".encodeToByteArray())
        assertContentEquals(a, r.cas.get(cid))
    } }

    @Test fun staleManifestCannotRollBackCommittedHead(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("manifest-head")
        r.scopes.uploadPut(scope.name, "source.bin", "old source".encodeToByteArray())
        val oldManifest = r.manifest(scope.name).readText()
        val current = r.scopes.uploadPut(scope.name, "source.bin", "new committed source".encodeToByteArray())
        r.manifest(scope.name).writeText(oldManifest) // simulate interruption before the new manifest append
        r.restart()
        assertEquals(current, r.registry.get(scope.name)!!.gateway.getAttachmentRef("source.bin")!!.contentId)
    } }

    @Test fun staleManifestCannotResurrectCommittedDeletion(): Unit = runBlocking { fixture { r ->
        val scope = r.scopes.beginUpload("manifest-delete")
        val bytes = "source deleted after durable publication".encodeToByteArray()
        val cid = r.scopes.uploadPut(scope.name, "source.bin", bytes)
        val oldManifest = r.manifest(scope.name).readText()
        r.scopes.uploadRemove(scope.name, "source.bin")
        r.manifest(scope.name).writeText(oldManifest) // simulate interruption before manifest cleanup
        r.restart()
        assertTrue(r.registry.get(scope.name)!!.store.head.isDeleted("source.bin"))
        assertContentEquals(bytes, r.cas.get(cid))
    } }
}
