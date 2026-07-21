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
        val existingMap = mutableMapOf<String, OroborosAttachmentRef>()
        for (ref in attachments.listAttachments(GIT_PREFIX)) {
            existingMap[ref.path] = ref
        }

        for ((logicalPath, physicalPath) in current) {
            val bytes = fileOps.readAllBytes(physicalPath)
            val cid = ContentId.of(bytes)
            val previous = existingMap[logicalPath]
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
        for ((path, ref) in existingMap) {
            if (path !in currentPaths) attachments.deleteAttachment(path, ref.revision)
        }

        return Snapshot(revision, currentPaths.sorted())
    }

    /** Restore a complete local .git database from Couch/CAS. */
    fun restore(forgeHome: String): Snapshot {
        val refsList = attachments.listAttachments(GIT_PREFIX)
        for (ref in refsList) {
            val stored = attachments.getAttachment(ref.path)
                ?: error("missing Git attachment ${ref.path}")
            val relative = ref.path.removePrefix(GIT_PREFIX)
            val target = fileOps.resolvePath(forgeHome, GIT_DIR, relative)
            parentOf(target)?.let { parent ->
                if (!fileOps.exists(parent)) fileOps.mkdirs(parent)
            }
            fileOps.write(target, stored.second)
        }
        var maxSeq = 0L
        var maxRev = ""
        for (ref in refsList) {
            if (ref.sequence > maxSeq) {
                maxSeq = ref.sequence
                maxRev = ref.revision
            }
        }
        return Snapshot(maxRev, refsList.map { it.path }.sorted())
    }

    private fun collectFiles(gitRoot: String): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val queue = mutableListOf(gitRoot to "")
        while (queue.isNotEmpty()) {
            val (directory, relativeDirectory) = queue.removeAt(0)
            for (name in fileOps.listDir(directory).sorted()) {
                val fullPath = fileOps.resolvePath(directory, name)
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                if (fileOps.isDir(fullPath)) {
                    queue.add(fullPath to relative)
                } else {
                    files[relative] = fullPath
                }
            }
        }
        return files
    }

    private fun contentType(path: String): String = when {
        path.endsWith(".go") -> "text/plain"
        path.endsWith(".kt") -> "text/kotlin"
        path.endsWith(".kts") -> "text/kotlin"
        path.endsWith(".md") -> "text/markdown"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".sh") -> "application/x-sh"
        path.endsWith(".bash") -> "application/x-sh"
        path.endsWith(".txt") -> "text/plain"
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".xml") -> "application/xml"
        path.endsWith(".yaml") || path.endsWith(".yml") -> "application/yaml"
        path.endsWith(".toml") -> "application/toml"
        path.endsWith(".csv") -> "text/csv"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".pdf") -> "application/pdf"
        path.endsWith(".zip") -> "application/zip"
        path.endsWith(".tar") -> "application/x-tar"
        path.endsWith(".gz") -> "application/gzip"
        path.endsWith(".bz2") -> "application/x-bzip2"
        path.endsWith(".xz") -> "application/x-xz"
        path.endsWith(".zst") -> "application/zstd"
        GIT_OBJECTS_DIR in path -> "application/x-git-object"
        GIT_REFS_DIR in path -> "application/x-git-ref"
        else -> "application/octet-stream"
    }

    companion object {
        const val GIT_DIR = ".git"
        const val GIT_PREFIX = "$GIT_DIR/"
        private const val GIT_OBJECTS_DIR = "$GIT_DIR/objects/"
        private const val GIT_REFS_DIR = "$GIT_DIR/refs/"

        private fun parentOf(path: String): String? {
            val lastSep = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (lastSep > 0) path.substring(0, lastSep) else null
        }
    }
}
