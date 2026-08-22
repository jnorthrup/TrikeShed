package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.util.io.ContentTypes

/**
 * Mirrors the repository working tree into Couch metadata backed by CAS blobs.
 *
 * This complements [GitCouchGateway], which mirrors the embedded Git database. Together
 * they place the complete repository under the Couch layer: Git database for
 * identity/history, working-tree files for source and document retrieval.
 */
class WorktreeCouchGateway(
    private val fileOps: FileOperations,
    private val attachments: CouchAttachmentGateway,
) {
    data class Snapshot(
        val revision: String,
        val paths: List<String>,
        val deletedPaths: List<String> = emptyList(),
    )

    fun reconcile(
        repoRoot: String,
        agentId: String,
        revision: String,
        sequence: Long,
    ): Snapshot {
        if (!fileOps.isDir(repoRoot)) return Snapshot(revision, emptyList())

        val current = collectFiles(repoRoot)
        val existing = attachments.listAttachments(WORKTREE_PREFIX).associateBy { it.path }

        for ((relativePath, physicalPath) in current) {
            val logicalPath = WORKTREE_PREFIX + relativePath
            // TOCTOU guard: the walk and the read are separate syscalls; a file
            // deleted between them (reactive editor, daemon self-write, git
            // checkout) used to abort the whole reconcile with FileNotFound —
            // and per-path tombstoning below keeps the next pass consistent.
            val bytes = try {
                fileOps.readAllBytes(physicalPath)
            } catch (_: Exception) {
                null
            } ?: continue
            val cid = ContentId.of(bytes)
            if (existing[logicalPath]?.contentId == cid) continue

            attachments.putAttachment(
                OroborosAttachmentRef(
                    path = logicalPath,
                    contentType = ContentTypes.forPath(relativePath),
                    length = bytes.size.toLong(),
                    contentId = cid,
                    agentId = agentId,
                    revision = revision,
                    sequence = sequence,
                ),
                bytes,
            )
        }

        val currentLogicalPaths = current.keys.mapTo(mutableSetOf()) { WORKTREE_PREFIX + it }
        val deletedPaths = mutableListOf<String>()
        for ((path, ref) in existing) {
            if (path !in currentLogicalPaths) {
                attachments.deleteAttachment(path, ref.revision)
                deletedPaths.add(path)
            }
        }

        return Snapshot(revision, currentLogicalPaths.sorted(), deletedPaths.sorted())
    }

    private fun collectFiles(repoRoot: String): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val queue = mutableListOf(repoRoot to "")
        while (queue.isNotEmpty()) {
            val (directory, relativeDirectory) = queue.removeAt(0)
            for (name in fileOps.listDir(directory).sorted()) {
                if (name in EXCLUDED_SEGMENTS) continue
                val fullPath = fileOps.resolvePath(directory, name)
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                if (fileOps.isDir(fullPath)) {
                    queue.add(fullPath to relative)
                } else if (fileOps.isFile(fullPath)) {
                    files[relative] = fullPath
                }
            }
        }
        return files
    }

    companion object {
        const val WORKTREE_PREFIX = "projects/trikeshed/"

        private val EXCLUDED_SEGMENTS = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules",
        )
    }
}
