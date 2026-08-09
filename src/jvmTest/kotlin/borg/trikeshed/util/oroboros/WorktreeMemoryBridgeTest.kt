package borg.trikeshed.util.oroboros

import borg.trikeshed.cas.IpfsBridge
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.memory.IndexKind
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorktreeMemoryBridgeTest {
    @Test
    fun worktreeCouchCasMemorySpineAndIpnsCompose() {
        val root = Files.createTempDirectory("oroboros-worktree-")
        val forge = Files.createTempDirectory("oroboros-forge-")
        try {
            root.resolve("README.md").writeText("# TrikeShed\n\nReactor memory.\n")
            root.resolve("src").createDirectories()
            root.resolve("src/Main.kt").writeText("fun main() = Unit\n")
            root.resolve("build").createDirectories()
            root.resolve("build/ignored.md").writeText("# generated\n")

            val fileOps = JvmFileOperations()
            val cas = FileCasStore(fileOps, forge.resolve("cas").toString())
            val couch = CouchStoreFactory.inMemory()
            val attachments = CouchAttachmentGateway(couch, cas)
            val memory = MemoryStore(cas, couch)
            val indexes = MemoryIndexLayer(memory)
            val ipfs = IpfsBridge(cas)
            val worktree = WorktreeCouchGateway(fileOps, attachments)
            val bridge = MemoryBridge(memory, attachments, ipfs)

            val snapshot = worktree.reconcile(
                repoRoot = root.toString(),
                agentId = "test",
                revision = "abc123",
                sequence = 1L,
            )

            assertEquals(2, snapshot.paths.size)
            assertNotNull(attachments.getAttachment("projects/trikeshed/README.md"))
            assertNotNull(attachments.getAttachment("projects/trikeshed/src/Main.kt"))
            assertNull(attachments.getAttachment("projects/trikeshed/build/ignored.md"))

            assertEquals(1, bridge.bridge(snapshot, "test"))
            assertEquals(0, bridge.bridge(snapshot, "test"), "unchanged projection must be idempotent")

            val memoryPath = "/memories/projects/trikeshed/README.md"
            assertNotNull(memory.get(memoryPath))
            assertEquals(1, memory.listPaths().size)
            assertEquals(memoryPath, memory.listPaths()[0])
            assertEquals(1, indexes.route(IndexKind.Taxonomy).entryCount)

            val spineCid = assertNotNull(memory.spineCidOf(memoryPath))
            assertEquals(spineCid, ipfs.resolveIpns("memory:$memoryPath"))
            assertTrue(memory.spineOf(memoryPath) != null)

            root.resolve("README.md").deleteIfExists()
            val afterDelete = worktree.reconcile(
                repoRoot = root.toString(),
                agentId = "test",
                revision = "def456",
                sequence = 2L,
            )
            assertEquals(listOf("projects/trikeshed/README.md"), afterDelete.deletedPaths)
            assertEquals(1, bridge.bridge(afterDelete, "test"))
            assertNull(memory.get(memoryPath))
            assertNull(ipfs.resolveIpns("memory:$memoryPath"))
            assertEquals(0, indexes.route(IndexKind.Taxonomy).entryCount)

            indexes.close()
        } finally {
            root.toFile().deleteRecursively()
            forge.toFile().deleteRecursively()
        }
    }
}
