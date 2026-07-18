package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

enum class FileEventType {
    CREATE, MODIFY, DELETE
}

data class FileEvent(val path: String, val type: FileEventType)

class FileEWatcher(
    private val baseDir: String,
    private val fileOps: FileOperations,
    private val ignorePatterns: List<String> = emptyList()
) {
    // Deterministic bounded output channel
    private val eventChannel = Channel<List<FileEvent>>(100)

    // Coalesced events (latest event per path wins)
    private val pendingEvents = mutableMapOf<String, FileEventType>()

    private val knownFiles = mutableSetOf<String>()

    // Store content hash for known files to detect modifications
    private val knownHashes = mutableMapOf<String, Int>()

    fun startProvisioning() {
        if (!fileOps.exists(baseDir)) {
            fileOps.mkdirs(baseDir)
        }
        scanRecursively(baseDir)
    }

    private fun isIgnored(path: String): Boolean {
        return ignorePatterns.any { pattern ->
            path.contains(pattern) || path.endsWith(pattern)
        }
    }

    private fun hashFileContent(path: String): Int {
        return try {
            fileOps.readAllBytes(path).contentHashCode()
        } catch (e: Exception) {
            0
        }
    }

    private fun scanRecursively(dir: String) {
        val currentFiles = mutableSetOf<String>()
        val queue = mutableListOf(dir)

        while (queue.isNotEmpty()) {
            val currentDir = queue.removeFirst()
            if (isIgnored(currentDir)) continue

            try {
                val list = fileOps.listDir(currentDir)
                for (name in list) {
                    val fullPath = fileOps.resolvePath(currentDir, name)
                    if (isIgnored(fullPath)) continue

                    if (fileOps.isDir(fullPath)) {
                        queue.add(fullPath)
                    } else if (fileOps.isFile(fullPath)) {
                        currentFiles.add(fullPath)
                        val currentHash = hashFileContent(fullPath)

                        if (!knownFiles.contains(fullPath)) {
                            knownFiles.add(fullPath)
                            knownHashes[fullPath] = currentHash
                            pendingEvents[fullPath] = FileEventType.CREATE
                        } else {
                            val previousHash = knownHashes[fullPath]
                            if (previousHash != currentHash) {
                                knownHashes[fullPath] = currentHash
                                pendingEvents[fullPath] = FileEventType.MODIFY
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // directory might have been deleted while scanning
            }
        }

        // Find deleted files
        val deleted = knownFiles.filter { !currentFiles.contains(it) }
        for (del in deleted) {
            knownFiles.remove(del)
            knownHashes.remove(del)
            pendingEvents[del] = FileEventType.DELETE
        }
    }

    // For test use or external modification notifications
    fun recordEvent(path: String, type: FileEventType) {
        if (isIgnored(path)) return
        pendingEvents[path] = type
    }

    suspend fun drain() {
        if (pendingEvents.isEmpty()) return

        val batch = pendingEvents.map { FileEvent(it.key, it.value) }
        pendingEvents.clear()

        eventChannel.send(batch)
    }

    fun getChannel(): ReceiveChannel<List<FileEvent>> = eventChannel

    // explicit drain semantics (close the channel)
    fun close() {
        eventChannel.close()
    }
}
