package borg.trikeshed.util.oroboros

import borg.trikeshed.job.Sha256Pure
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * CouchDB attachment metadata representing one tracked file in the managed repository tree.
 */
data class ProjectTreeAttachment(
    val contentType: String,
    val length: Long,
    val digest: String, // hex SHA-256
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ProjectTreeAttachment
        if (contentType != other.contentType) return false
        if (length != other.length) return false
        if (digest != other.digest) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = contentType.hashCode()
        result = 31 * result + length.hashCode()
        result = 31 * result + digest.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Evaluates a directory tree into a map of normalized relative paths to their CouchDB attachment models.
 */
object ProjectTreeAttachments {
    
    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") {
        it.toInt().and(0xFF).toString(16).padStart(2, '0')
    }

    private fun inferContentType(path: String): String {
        return when {
            path.endsWith(".md") -> "text/markdown"
            path.endsWith(".kt") -> "text/kotlin"
            path.endsWith(".java") -> "text/x-java-source"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".txt") -> "text/plain"
            path.endsWith(".xml") -> "text/xml"
            else -> "application/octet-stream"
        }
    }

    fun build(
        fileOps: FileOperations,
        rootPath: String,
        ignorePredicate: (String) -> Boolean = { false },
        onBrokenSymlinkOrSpecialFile: (String) -> Unit = {}
    ): Map<String, ProjectTreeAttachment> {
        val result = mutableMapOf<String, ProjectTreeAttachment>()
        
        fun walk(currentPath: String, relPath: String) {
            val normalizedRelPath = relPath.replace('\\', '/')
            if (relPath.isNotEmpty() && ignorePredicate(normalizedRelPath)) {
                return
            }

            if (fileOps.isDir(currentPath)) {
                val children = try {
                    fileOps.listDir(currentPath)
                } catch (e: Exception) {
                    emptyList() // e.g. permission denied or broken symlink behaving like dir
                }
                for (child in children) {
                    val childPath = fileOps.resolvePath(currentPath, child)
                    val childRelPath = if (relPath.isEmpty()) child else "$relPath/$child"
                    walk(childPath, childRelPath)
                }
            } else if (fileOps.isFile(currentPath)) {
                try {
                    val data = fileOps.readAllBytes(currentPath)
                    val contentType = inferContentType(normalizedRelPath)
                    val digestBytes = Sha256Pure.digest(data)
                    val digestHex = toHex(digestBytes)
                    result[normalizedRelPath] = ProjectTreeAttachment(
                        contentType = contentType,
                        length = data.size.toLong(),
                        digest = digestHex,
                        data = data
                    )
                } catch (e: Exception) {
                    // Could be broken symlink or special file (e.g., named pipe) that throws on read
                    onBrokenSymlinkOrSpecialFile(normalizedRelPath)
                }
            } else {
                // Not a dir, not a regular file -> broken symlink or special file
                if (relPath.isNotEmpty()) {
                    onBrokenSymlinkOrSpecialFile(normalizedRelPath)
                }
            }
        }

        walk(rootPath, "")
        return result
    }
}
