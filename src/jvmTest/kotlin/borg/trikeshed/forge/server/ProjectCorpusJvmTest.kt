package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daemon's corpus over a real mounted folder: metadata listing, text reads, and a revision moving the cid. */
class ProjectCorpusJvmTest {

    private fun rig(): Pair<ProjectScopes, ProjectDbRegistry> {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val bridge = CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couchStore)))
        val registry = ProjectDbRegistry("trikeshed")
        val home = File(System.getProperty("java.io.tmpdir"), "corpus-home-${System.nanoTime()}").apply { mkdirs() }
        val scopes = ProjectScopes(JvmFileOperations(), gateway, bridge, cas, null, projectDbs = registry, ledgerFile = File(home, "mount-ledger.tsv"), filesRoot = File(home, "files"))
        return scopes to registry
    }

    private fun folder(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "corpus-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "a.md").writeText("alpha")
        File(dir, "b.md").writeText("beta")
        File(dir, "c.pdf").writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 1, 2, 3))
        return dir
    }

    @Test
    fun listsReadsAndSeesARevisionMoveTheCid(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.mount(folder().absolutePath)
        val corpus = JvmProjectCorpus(registry, scopes)
        val refs = corpus.projects()
        assertTrue(refs.any { it.name == scope.name && it.docs >= 3 }, "$refs")
        val md = corpus.docs(scope.name, glob = "*.md")
        assertEquals(listOf("a.md", "b.md"), md.map { it.id })
        assertEquals(ContentId.of("alpha".encodeToByteArray()).value, md[0].cid)
        // The store numbers commits from 0; every mounted document carries its own commit sequence.
        assertTrue(md.all { it.seq >= 0 } && md.map { it.seq }.distinct().size == md.size, "each mounted document carries its own store commit sequence: $md")
        val text = corpus.read(scope.name, "a.md")!!
        assertEquals("alpha", text.text); assertEquals(md[0].cid, text.cid)
        assertNull(corpus.read(scope.name, "c.pdf"), "a PDF is not read as text")
        assertNull(corpus.read(scope.name, "nosuch.md"))
        val before = md[0]
        scopes.uploadPut(scope.name, "a.md", "alpha changed".encodeToByteArray())
        val after = corpus.docs(scope.name, glob = "a.md").single()
        assertNotEquals(before.cid, after.cid); assertNotEquals(before.rev, after.rev)
        assertTrue(after.seq > before.seq, "a revision moves the commit sequence forward: ${before.seq} -> ${after.seq}")
        assertEquals("alpha changed", corpus.read(scope.name, "a.md")!!.text)
        val docs = ProjectNodes.registry(corpus).getValue(ProjectNodes.DOCS)
        val out = docs.run(borg.trikeshed.lcnc.LcncNode("d", ProjectNodes.DOCS, params = mapOf("project" to scope.name, "glob" to "*.md")), emptyMap())
        assertEquals(2, out["count"])
    }
}
