package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GitCouchReplicationIdentityTest {
    @Test
    fun gitNamespaceSurvivesReconcileUpdateDeleteAndRestore() {
        val files = InMemoryFileOperations()
        files.write("/source/.git/HEAD", "ref: refs/heads/main\n")
        files.write("/source/.git/refs/heads/main", "before\n")
        files.write("/source/.git/logs/stale", "old\n")
        val cas = CasStore.inMemory()
        val attachments = CouchAttachmentGateway(CouchStoreFactory.casBacked(cas), cas)
        val gateway = GitCouchGateway(files, attachments)
        val initial = gateway.reconcile("/source", "test", "before", 1)
        assertEquals(listOf(".git/HEAD", ".git/logs/stale", ".git/refs/heads/main"), initial.paths)
        files.write("/source/.git/refs/heads/main", "after\n")
        files.deleteRecursively("/source/.git/logs/stale")
        val changed = gateway.reconcile("/source", "test", "after", 2)
        assertEquals(listOf(".git/HEAD", ".git/refs/heads/main"), changed.paths)
        assertFalse(attachments.listAttachments(".git/").any { it.path == ".git/logs/stale" })
        gateway.restore("/destination")
        assertContentEquals("ref: refs/heads/main\n".encodeToByteArray(), files.readAllBytes("/destination/.git/HEAD"))
        assertContentEquals("after\n".encodeToByteArray(), files.readAllBytes("/destination/.git/refs/heads/main"))
    }
}
