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
    /** Logical prefix every absorbed path is filed under. */
    private val prefix: String = WORKTREE_PREFIX,
    /** Directory/file names skipped at any depth. */
    private val excludedSegments: Set<String> = EXCLUDED_SEGMENTS,
    /** Relative paths (from the root) skipped as subtrees, e.g. `.claude/worktrees`. */
    private val excludedRelativePrefixes: Set<String> = EXCLUDED_RELATIVE_PREFIXES,
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
        val existing = attachments.listAttachments(prefix).associateBy { it.path }

        for ((relativePath, physicalPath) in current) {
            val logicalPath = prefix + relativePath
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

        val currentLogicalPaths = current.keys.mapTo(mutableSetOf()) { prefix + it }
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
                if (name in excludedSegments) continue
                val fullPath = fileOps.resolvePath(directory, name)
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                if (excludedRelativePrefixes.any { relative == it || relative.startsWith("$it/") }) continue
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

        val EXCLUDED_SEGMENTS = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules",
        )

        /** Agent worktree clones are other checkouts, not this project's history. */
        val EXCLUDED_RELATIVE_PREFIXES = setOf(".claude/worktrees")
    }
}
