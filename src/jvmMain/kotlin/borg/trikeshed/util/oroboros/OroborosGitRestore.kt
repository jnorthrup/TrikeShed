package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations

class OroborosGitRestore(
    private val fileOps: FileOperations,
    private val processOps: ProcessOperations
) {
    suspend fun restore(targetDir: String, attachments: List<Pair<OroborosAttachmentRef, ByteArray>>) {
        if (attachments.isEmpty()) return

        for ((ref, bytes) in attachments) {
            val path = ref.path

            if (path.startsWith("/")) {
                throw IllegalArgumentException("Absolute paths are not allowed: $path")
            }
            if (path.contains("..")) {
                throw IllegalArgumentException("Path traversal (..) is not allowed: $path")
            }

            // We may have prefix like projects/trikeshed/ - but the task asks to
            // use normalized safe paths. We'll simply use the attachment path directly
            // or perhaps the attachment path is already relative. The prompt says "writes one CouchDB project record attachment set into an empty managed root using normalized safe paths".
            
            val targetPath = fileOps.resolvePath(targetDir, path)
            
            // Create parent directory
            val separator = maxOf(targetPath.lastIndexOf('/'), targetPath.lastIndexOf('\\'))
            if (separator > 0) {
                val parentDir = targetPath.substring(0, separator)
                if (!fileOps.exists(parentDir)) {
                    fileOps.mkdirs(parentDir)
                }
            }
            
            fileOps.write(targetPath, bytes)
        }

        // git fsck
        val fsckResult = processOps.exec("git", listOf("-C", targetDir, "fsck"))
        if (fsckResult.exitCode != 0) {
            throw IllegalStateException("git fsck failed: ${fsckResult.stderr.decodeToString()}")
        }

        // git checkout
        val revision = attachments.first().first.revision
        if (revision.isNotEmpty()) {
            val checkoutResult = processOps.exec("git", listOf("-C", targetDir, "checkout", "-f", revision))
            if (checkoutResult.exitCode != 0) {
                throw IllegalStateException("git checkout failed: ${checkoutResult.stderr.decodeToString()}")
            }
        }
    }
}
