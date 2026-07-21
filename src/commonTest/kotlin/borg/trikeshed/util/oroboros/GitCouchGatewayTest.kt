package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCouchGatewayTest {
    @Test
    fun recursiveGitDatabaseReconcilesIntoCouchAndRestores() {
        val fileOps = InMemoryFileOperations()
        val forgeHome = "/forge"
        val gitRoot = fileOps.resolvePath(forgeHome, ".git")
        val head = fileOps.resolvePath(gitRoot, "HEAD")
        val mainRef = fileOps.resolvePath(gitRoot, "refs", "heads", "main")
        val stale = fileOps.resolvePath(gitRoot, "logs", "stale")
        fileOps.write(head, "ref: refs/heads/main\n")
        fileOps.write(mainRef, "abc123\n")
        fileOps.write(stale, "old\n")

        val attachments = CouchAttachmentGateway(CouchStoreFactory.inMemory(), CasStore.inMemory())
        val gateway = GitCouchGateway(fileOps, attachments)

        val first = gateway.reconcile(forgeHome, "agent", "abc123", 1)
        assertEquals(listOf(".git/HEAD", ".git/logs/stale", ".git/refs/heads/main"), first.paths)
        assertContentEquals("ref: refs/heads/main\n".encodeToByteArray(), attachments.getAttachment(".git/HEAD")!!.second)

        fileOps.write(mainRef, "def456\n")
        fileOps.deleteRecursively(stale)
        val second = gateway.reconcile(forgeHome, "agent", "def456", 2)
        assertEquals(listOf(".git/HEAD", ".git/refs/heads/main"), second.paths)
        assertEquals("def456\n", attachments.getAttachment(".git/refs/heads/main")!!.second.decodeToString())
        assertFalse(attachments.listAttachments(".git/").any { it.path == ".git/logs/stale" })

        fileOps.deleteRecursively(gitRoot)
        assertFalse(fileOps.exists(head))
        val restored = gateway.restore(forgeHome)
        assertEquals("def456", restored.revision)
        assertTrue(fileOps.exists(head))
        assertEquals("def456\n", fileOps.readString(mainRef))
    }
}