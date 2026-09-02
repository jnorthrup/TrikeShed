package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-09-01: an 8 GB Blu-ray remux a download dropped into the repo root (untracked,
 * gitignored) made the worktree reconcile call `readAllBytes` on it at every boot —
 * `OutOfMemoryError: Required array size too large` — and the whole reconcile died with it.
 * The gateway now stats before it reads: a file over the cap is skipped, NAMED in the
 * snapshot, and never opened for content. These tests pin that, and that the cap is a
 * membership rule (a file that grows past it leaves the plane), with a 2-byte cap so no
 * test writes a gigabyte.
 */
class WorktreeOversizedFileTest {

    private class Rig {
        val root = Files.createTempDirectory("worktree-oversized-")
        val forge = Files.createTempDirectory("worktree-oversized-forge-")
        val fileOps = JvmFileOperations()
        val attachments = CouchAttachmentGateway(CouchStoreFactory.inMemory(), FileCasStore(fileOps, forge.resolve("cas").toString()))
        fun gateway(cap: Long) = WorktreeCouchGateway(fileOps, attachments, maxFileBytes = cap)
        fun close() { root.toFile().deleteRecursively(); forge.toFile().deleteRecursively() }
    }

    @Test
    fun aFileOverTheCapIsSkippedByNameAndNeverAbsorbed() {
        val r = Rig()
        try {
            r.root.resolve("README.md").writeText("ok")                 // 2 bytes: at the cap, absorbed
            r.root.resolve("remux.mkv").writeBytes(ByteArray(3))          // 3 bytes: over it, skipped
            val snap = r.gateway(cap = 2L).reconcile(r.root.toString(), "test", "r1", 1L)
            assertEquals(listOf("remux.mkv"), snap.skippedFiles)
            assertEquals(listOf(WorktreeCouchGateway.WORKTREE_PREFIX + "README.md"), snap.paths)
            assertTrue(r.attachments.listAttachments(WorktreeCouchGateway.WORKTREE_PREFIX).none { it.path.endsWith("remux.mkv") })
            assertTrue(snap.skippedDirs.isEmpty(), "a big file is not an unreadable directory")
        } finally { r.close() }
    }

    @Test
    fun aFileThatGrowsPastTheCapLeavesThePlane() {
        val r = Rig()
        try {
            val doc = r.root.resolve("notes.txt")
            doc.writeText("hi")
            val first = r.gateway(cap = 2L).reconcile(r.root.toString(), "test", "r1", 1L)
            assertEquals(1, first.paths.size)
            doc.writeText("hi there")
            val second = r.gateway(cap = 2L).reconcile(r.root.toString(), "test", "r2", 2L)
            assertEquals(listOf("notes.txt"), second.skippedFiles)
            assertTrue(second.paths.isEmpty())
            assertEquals(listOf(WorktreeCouchGateway.WORKTREE_PREFIX + "notes.txt"), second.deletedPaths)
            assertFalse(r.attachments.listAttachments(WorktreeCouchGateway.WORKTREE_PREFIX).any { it.path.endsWith("notes.txt") })
        } finally { r.close() }
    }

    @Test
    fun theDefaultCapIsWellUnderTheJvmArrayLimit() {
        assertTrue(WorktreeCouchGateway.MAX_FILE_BYTES < Int.MAX_VALUE.toLong(), "a readAllBytes must always be possible under the cap")
        assertEquals(64L shl 20, WorktreeCouchGateway.MAX_FILE_BYTES)
    }
}
