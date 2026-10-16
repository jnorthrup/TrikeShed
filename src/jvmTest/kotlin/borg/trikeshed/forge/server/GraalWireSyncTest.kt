package borg.trikeshed.forge.server

import borg.trikeshed.cas.IpfsBridge
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.job.CasStore
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.MemoryBridge
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import borg.trikeshed.util.oroboros.element.WorktreeReconcileElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraalWireSyncTest {
    class Fixture(context: CoroutineContext, headSha: suspend () -> String = { "revision" }) {
        val files = InMemoryFileOperations()
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val attachments = CouchAttachmentGateway(store, cas)
        val memory = MemoryStore(cas, store)
        val index = MemoryIndexLayer(memory)
        val element = WorktreeReconcileElement(
            "/repo", WorktreeCouchGateway(files, attachments), CouchIndexBridge(attachments, index),
            MemoryBridge(memory, attachments, IpfsBridge(cas)), headSha, context[Job],
        )
        val wire = GraalWire(JvmVitals(), store, null, CoroutineScope(context + element))

        init { files.mkdirs("/repo") }

        suspend fun close() {
            element.drain()
            index.close()
        }
    }

    @Test
    fun syncRemovesDeletedFilesAndMemoryFromTerrainBeforeReturning() = runTest {
        val h = Fixture(coroutineContext)
        val prefix = WorktreeCouchGateway.WORKTREE_PREFIX
        h.files.write("/repo/removed.md", "# Removed\n")
        h.files.write("/repo/retained.md", "# Retained\n")
        h.element.open()
        try {
            assertEquals(200, h.wire.route("POST", "/api/graal/sync", "", null)!!.status)
            assertNotNull(h.attachments.getAttachmentRef("${prefix}removed.md"))

            h.files.deleteRecursively("/repo/removed.md")
            val response = h.wire.route("POST", "/api/graal/sync", "", null)!!
            assertEquals(200, response.status)
            val body = reifyMap(response.body)
            assertEquals(1, (body["paths"] as Number).toInt())
            assertEquals(1, (body["deleted"] as Number).toInt())
            assertEquals(emptyList<Any?>(), body["skippedDirs"])
            assertEquals(emptyList<Any?>(), body["skippedFiles"])
            assertEquals(null, h.attachments.getAttachmentRef("${prefix}removed.md"))
            assertNotNull(h.attachments.getAttachmentRef("${prefix}retained.md"))

            val map = h.wire.route("GET", "/api/graal/map", "", null)!!
            assertFalse(map.body.contains("removed.md"), "Source and memory projections leave the terrain together")
            assertTrue(map.body.contains("${prefix}retained.md"))
            val removedRevision = h.store.head.getRev("${prefix}removed.md")
            val retainedRevision = h.store.head.getRev("${prefix}retained.md")
            val repeated = h.wire.route("POST", "/api/graal/sync", "", null)!!
            assertEquals(200, repeated.status)
            assertEquals(0, (reifyMap(repeated.body)["deleted"] as Number).toInt())
            assertEquals(removedRevision, h.store.head.getRev("${prefix}removed.md"))
            assertEquals(retainedRevision, h.store.head.getRev("${prefix}retained.md"))
        } finally {
            h.close()
        }
    }

    @Test
    fun syncWithoutWorktreeElementReturnsUnavailable() = runTest {
        val wire = GraalWire(JvmVitals(), null, null, this)
        val response = wire.route("POST", "/api/graal/sync", "", null)!!
        assertEquals(503, response.status)
        assertEquals("worktree_sync_unavailable", reifyMap(response.body)["error"])
    }

    @Test
    fun failedSyncReturnsErrorAndWorkerAcceptsNextRequest() = runTest {
        var fail = true
        val h = Fixture(coroutineContext) {
            check(!fail) { "head unavailable" }
            "revision"
        }
        h.element.open()
        try {
            val failed = h.wire.route("POST", "/api/graal/sync", "", null)!!
            assertEquals(500, failed.status)
            assertEquals("worktree_sync_failed", reifyMap(failed.body)["error"])
            fail = false
            assertEquals(200, h.wire.route("POST", "/api/graal/sync", "", null)!!.status)
        } finally {
            h.close()
        }
    }
}
