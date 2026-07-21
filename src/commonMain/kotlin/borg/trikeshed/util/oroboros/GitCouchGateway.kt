package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Mirrors the local Git database into TrikeShed Couch attachment references.
 *
 * The rxf-rsync minimum is recursive reconciliation: new/changed files are
 * attached, absent files are tombstoned, and unchanged digests are skipped.
 * Bytes remain single-copy in CAS through [CouchAttachmentGateway].
 */
class GitCouchGateway(
    private val fileOps: FileOperations,
    private val attachments: CouchAttachmentGateway,
) {
    data class Snapshot(
        val revision: String,
        val paths: List<String>,
    )

    fun reconcile(
        forgeHome: String,
        agentId: String,
        revision: String,
        sequence: Long,
    ): Snapshot {
        val gitRoot = fileOps.resolvePath(forgeHome, GIT_DIR)
        if (!fileOps.isDir(gitRoot)) return Snapshot(revision, emptyList())

        val current = collectFiles(gitRoot)
        val existing = attachments.listAttachments(GIT_PREFIX).associateBy { it.path }

        for ((logicalPath, physicalPath) in current) {
            val bytes = fileOps.readAllBytes(physicalPath)
            val cid = ContentId.of(bytes)
            val previous = existing[logicalPath]
            if (previous?.contentId == cid) continue

            attachments.putAttachment(
                OroborosAttachmentRef(
                    path = logicalPath,
                    contentType = contentType(logicalPath),
                    length = bytes.size.toLong(),
                    contentId = cid,
                    agentId = agentId,
                    revision = revision,
                    sequence = sequence,
                ),
                bytes,
            )
        }

        val currentPaths = current.keys
        for ((path, ref) in existing) {
            if (path !in currentPaths) attachments.deleteAttachment(path, ref.revision)
        }

        return Snapshot(revision, currentPaths.sorted())
    }

    /** Restore a complete local .git database from Couch/CAS. */
    fun restore(forgeHome: String): Snapshot {
        val refs = attachments.listAttachments(GIT_PREFIX)
        for (ref in refs) {
            val stored = attachments.getAttachment(ref.path)
                ?: error("missing Git attachment ${ref.path}")
            val relative = ref.path.removePrefix(GIT_PREFIX)
            val target = fileOps.resolvePath(forgeHome, GIT_DIR, relative)
            parentOf(target)?.let { parent ->
                if (!fileOps.exists(parent)) fileOps.mkdirs(parent)
            }
            fileOps.write(target, stored.second)
        }
        val revision = refs.maxByOrNull { it.sequence }?.revision.orEmpty()
        return Snapshot(revision, refs.map { it.path }.sorted())
    }

    private fun collectFiles(gitRoot: String): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val queue = mutableListOf(gitRoot to "")
        while (queue.isNotEmpty()) {
            val (directory, relativeDirectory) = queue.removeAt(0)
            for (name in fileOps.listDir(directory).sorted()) {
                val physical = fileOps.resolvePath(directory, name)
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                when {
                    fileOps.isDir(physical) -> queue += physical to relative
                    fileOps.isFile(physical) && !name.endsWith(".lock") -> files["$GIT_PREFIX$relative"] = physical
                }
            }
        }
        return files.toSortedMap()
    }

    private fun contentType(path: String): String = when {
        path.endsWith("/HEAD") || path.endsWith("/config") || path.contains("/refs/") -> "text/plain"
        else -> "application/x-git-object"
    }

    private fun parentOf(path: String): String? {
        val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (slash > 0) path.substring(0, slash) else null
    }

    private companion object {
        const val GIT_DIR = ".git"
        const val GIT_PREFIX = ".git/"
    }
}
