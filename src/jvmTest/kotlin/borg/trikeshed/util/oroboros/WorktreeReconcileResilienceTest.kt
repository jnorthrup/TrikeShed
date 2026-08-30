package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * VAL-CROSS-002 found the hosted daemon's Couch doc plane holding git refs ALONE: the whole
 * worktree reconcile had aborted on
 *
 *   Malformed input or input contains unmappable characters: /repo/.venv/bin/????thon
 *
 * — one filename the container's ASCII `sun.jnu.encoding` could not decode. The plane looked
 * empty rather than broken, which is why it went unnoticed until an assertion needed to read
 * through it.
 *
 * These tests pin the two properties that failure demanded: one unreadable directory costs its
 * own subtree and nothing else, and files under it are NOT tombstoned — absence caused by a
 * failed read is not evidence of deletion.
 */
class WorktreeReconcileResilienceTest {

    /** [JvmFileOperations] with one directory rigged to throw the way an undecodable name does. */
    private class FailingListDir(
        private val delegate: JvmFileOperations = JvmFileOperations(),
        private val failFor: (String) -> Boolean,
    ) : borg.trikeshed.userspace.nio.file.spi.FileOperations by delegate {
        override fun listDir(path: String): List<String> {
            if (failFor(path)) throw java.nio.charset.MalformedInputException(1)
            return delegate.listDir(path)
        }
    }

    @Test
    fun oneUnreadableDirectoryCostsItsSubtreeAndNotTheWholeWorktree() {
        val root = Files.createTempDirectory("worktree-resilience-")
        val forge = Files.createTempDirectory("worktree-resilience-forge-")
        try {
            root.resolve("README.md").writeText("# readable\n")
            root.resolve("src").createDirectories()
            root.resolve("src/Main.kt").writeText("fun main() = Unit\n")
            root.resolve(".venv/bin").createDirectories()
            root.resolve(".venv/bin/python").writeText("#!/usr/bin/env python\n")

            val fileOps = JvmFileOperations()
            val cas = FileCasStore(fileOps, forge.resolve("cas").toString())
            val attachments = CouchAttachmentGateway(CouchStoreFactory.inMemory(), cas)

            // First pass: everything readable, so the plane holds all three files.
            val healthy = WorktreeCouchGateway(fileOps, attachments).reconcile(
                repoRoot = root.toString(), agentId = "test", revision = "r1", sequence = 1L,
            )
            assertEquals(3, healthy.paths.size, "control: all three files absorbed")
            assertTrue(healthy.skippedDirs.isEmpty(), "control: nothing skipped")

            // Second pass: `.venv/bin` can no longer be enumerated.
            val degraded = WorktreeCouchGateway(
                FailingListDir(failFor = { it.endsWith("/.venv/bin") }), attachments,
            ).reconcile(repoRoot = root.toString(), agentId = "test", revision = "r2", sequence = 2L)

            // The reconcile completed instead of throwing, and kept the readable tree.
            assertEquals(
                listOf("projects/trikeshed/README.md", "projects/trikeshed/src/Main.kt"),
                degraded.paths,
                "an unreadable directory must not cost the readable tree",
            )
            assertEquals(listOf(".venv/bin"), degraded.skippedDirs, "the loss is reported, not hidden")

            // The decisive property: the unreadable subtree's file was NOT tombstoned. Before the
            // guard, absence from the walk read as deletion and destroyed a live attachment.
            assertEquals(emptyList(), degraded.deletedPaths, "a failed read is not a deletion")
            assertNotNull(
                attachments.getAttachment("projects/trikeshed/.venv/bin/python"),
                "attachment under the unreadable directory must survive",
            )
        } finally {
            root.toFile().deleteRecursively()
            forge.toFile().deleteRecursively()
        }
    }

    @Test
    fun genuineDeletionsAreStillTombstonedWhenTheWalkReachesTheirDirectory() {
        val root = Files.createTempDirectory("worktree-deletion-")
        val forge = Files.createTempDirectory("worktree-deletion-forge-")
        try {
            root.resolve("keep.md").writeText("keep\n")
            root.resolve("drop.md").writeText("drop\n")

            val fileOps = JvmFileOperations()
            val cas = FileCasStore(fileOps, forge.resolve("cas").toString())
            val attachments = CouchAttachmentGateway(CouchStoreFactory.inMemory(), cas)
            val gateway = WorktreeCouchGateway(fileOps, attachments)

            gateway.reconcile(root.toString(), "test", "r1", 1L)
            root.resolve("drop.md").toFile().delete()
            val after = gateway.reconcile(root.toString(), "test", "r2", 2L)

            assertEquals(listOf("projects/trikeshed/drop.md"), after.deletedPaths)
            assertTrue(after.skippedDirs.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
            forge.toFile().deleteRecursively()
        }
    }
}
