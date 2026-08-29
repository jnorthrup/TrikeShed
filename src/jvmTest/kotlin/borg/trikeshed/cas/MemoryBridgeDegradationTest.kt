package borg.trikeshed.cas

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.MemoryBridge
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.lib.get
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for VAL-CROSS-LAUNCH-001: IpfsBridge AIOOBE during
 * initial reconcile prevents reconcileBuildPlane from executing.
 *
 * Root cause: the entire initial reconcile was in a single runCatching block,
 * so any exception from memoryBridge.bridge() killed the build plane reconcile
 * and prevented classpath.tsv from being written.
 *
 * Fix: (1) MemoryBridge.bridge() catches per-path exceptions, (2) OroborosDaemon
 * splits the initial reconcile into independent runCatching blocks.
 */
class MemoryBridgeDegradationTest {

    /**
     * When publishIpns throws (the AIOOBE), bridge() must not propagate —
     * it degrades and continues processing remaining paths.
     */
    @Test
    fun bridgeDegradesOnPublishIpnsAioobe() {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.inMemory()
        val attachments = CouchAttachmentGateway(couch, cas)
        val memory = MemoryStore(cas, couch)

        // IpfsBridge that throws AIOOBE on every publishIpns call
        val brokenIpfs = object : IpfsBridge(cas) {
            override fun publishIpns(name: String, manifestCid: ContentId) {
                throw ArrayIndexOutOfBoundsException(
                    "OpenAddressingMap.set: slot=16 capacity=16"
                )
            }
        }
        val bridge = MemoryBridge(memory, attachments, brokenIpfs)

        // Two memory-eligible files
        val file1 = "# Doc One\n\nFirst document.\n".encodeToByteArray()
        val file2 = "# Doc Two\n\nSecond document.\n".encodeToByteArray()
        attachments.putAttachment(
            OroborosAttachmentRef(
                path = "projects/test/alpha.md", contentType = "text/markdown",
                length = file1.size.toLong(), contentId = ContentId.of(file1),
                agentId = "test", revision = "rev1", sequence = 1L,
            ), file1,
        )
        attachments.putAttachment(
            OroborosAttachmentRef(
                path = "projects/test/beta.md", contentType = "text/markdown",
                length = file2.size.toLong(), contentId = ContentId.of(file2),
                agentId = "test", revision = "rev1", sequence = 1L,
            ), file2,
        )

        val snapshot = WorktreeCouchGateway.Snapshot(
            revision = "rev1",
            paths = listOf("projects/test/alpha.md", "projects/test/beta.md"),
        )

        // CRITICAL: bridge() must NOT throw — the AIOOBE is caught per-path
        val bridged = bridge.bridge(snapshot, "test")

        // publishIpns threw for both files, so bridged==0 (bridged++ is after the throw)
        assertEquals(0, bridged, "bridge() should degrade, not crash")

        // Memory files were stored (put() succeeded before publishIpns threw)
        assertEquals(2, memory.listPaths().size, "memory files stored despite IPNS failure")
    }

    /**
     * When publishIpns AIOOBEs on the first file but succeeds on subsequent files,
     * bridge() must still process the successful ones.
     */
    @Test
    fun bridgePartialSuccessDespiteAioobe() {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.inMemory()
        val attachments = CouchAttachmentGateway(couch, cas)
        val memory = MemoryStore(cas, couch)

        // Crashes on first call, succeeds thereafter
        val crashingIpfs = object : IpfsBridge(cas) {
            private var calls = 0
            override fun publishIpns(name: String, manifestCid: ContentId) {
                calls++
                if (calls == 1) {
                    throw ArrayIndexOutOfBoundsException(
                        "OpenAddressingMap.set: slot=16 capacity=16 (first batch crash)"
                    )
                }
            }
        }
        val bridge = MemoryBridge(memory, attachments, crashingIpfs)

        val file1 = "# Alpha\n\nFirst.\n".encodeToByteArray()
        val file2 = "# Beta\n\nSecond.\n".encodeToByteArray()
        attachments.putAttachment(
            OroborosAttachmentRef(
                path = "projects/test/alpha.md", contentType = "text/markdown",
                length = file1.size.toLong(), contentId = ContentId.of(file1),
                agentId = "test", revision = "rev1", sequence = 1L,
            ), file1,
        )
        attachments.putAttachment(
            OroborosAttachmentRef(
                path = "projects/test/beta.md", contentType = "text/markdown",
                length = file2.size.toLong(), contentId = ContentId.of(file2),
                agentId = "test", revision = "rev1", sequence = 1L,
            ), file2,
        )

        val snapshot = WorktreeCouchGateway.Snapshot(
            revision = "rev1",
            paths = listOf("projects/test/alpha.md", "projects/test/beta.md"),
        )

        val bridged = bridge.bridge(snapshot, "test")

        // alpha crashed (call 1), beta succeeded (call 2)
        assertEquals(1, bridged, "second file bridged despite first file's AIOOBE")
    }

    /**
     * Existing happy-path behavior: bridge() returns the correct count and
     * IPNS entries are resolvable.
     */
    @Test
    fun bridgeHappyPathUnchanged() {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.inMemory()
        val attachments = CouchAttachmentGateway(couch, cas)
        val memory = MemoryStore(cas, couch)
        val ipfs = IpfsBridge(cas)
        val bridge = MemoryBridge(memory, attachments, ipfs)

        val file1 = "# Hello\n\nWorld.\n".encodeToByteArray()
        attachments.putAttachment(
            OroborosAttachmentRef(
                path = "projects/trikeshed/hello.md", contentType = "text/markdown",
                length = file1.size.toLong(), contentId = ContentId.of(file1),
                agentId = "test", revision = "rev1", sequence = 1L,
            ), file1,
        )

        val snapshot = WorktreeCouchGateway.Snapshot(
            revision = "rev1",
            paths = listOf("projects/trikeshed/hello.md"),
        )

        val bridged = bridge.bridge(snapshot, "test")
        assertEquals(1, bridged)

        val memoryPath = "/memories/projects/trikeshed/hello.md"
        assertEquals(memoryPath, memory.listPaths()[0])

        val spineCid = memory.spineCidOf(memoryPath)
        assertEquals(spineCid, ipfs.resolveIpns("memory:$memoryPath"))
    }
}
