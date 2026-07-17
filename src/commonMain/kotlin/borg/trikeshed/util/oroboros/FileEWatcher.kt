package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

data class FileEvent(
    val agentId: String,
    val path: String,
    val type: EventType,
    val contentId: ContentId? = null
) {
    enum class EventType { CREATED, MODIFIED, DELETED }
}

class FileEWatcher(
    val fileOps: FileOperations,
    val forgeHome: ForgeHome,
    val ignores: Set<String> = setOf(".git", ".pijul", ".oroboros")
) {
    // Finite channel; no unlimited, no drop oldest
    private val _events = Channel<FileEvent>(Channel.BUFFERED)
    val events: ReceiveChannel<FileEvent> get() = _events

    // State tracking to coalesce events by agent+path+ContentId
    private val knownHashes = mutableMapOf<Pair<String, String>, ContentId?>()

    /**
     * Recursively provisions a directory structure, starting the watch state.
     */
    suspend fun provision(agentId: String, path: String = "") {
        val fullPath = forgeHome.resolveAgentPath(agentId, path)
        if (!fileOps.exists(fullPath)) return

        if (fileOps.isDir(fullPath)) {
            val children = fileOps.listDir(fullPath)
            for (child in children) {
                if (child in ignores) continue
                provision(agentId, if (path.isEmpty()) child else "$path/$child")
            }
        } else {
            val bytes = fileOps.readAllBytes(fullPath)
            val hash = ContentId.of(bytes)
            knownHashes[agentId to path] = hash
            _events.send(FileEvent(agentId, path, FileEvent.EventType.CREATED, hash))
        }
    }

    /**
     * Reconciles the current file system state against known state to generate bounded deterministic batches.
     */
    suspend fun reconcile(agentId: String, path: String = "") {
        val fullPath = forgeHome.resolveAgentPath(agentId, path)

        if (!fileOps.exists(fullPath)) {
            // Check if it was known before; if so, it's deleted
            val deletedPaths = knownHashes.keys.filter {
                it.first == agentId && (
                    it.second == path ||
                    it.second.startsWith("$path/") ||
                    (path.isEmpty() && it.second.isNotEmpty())
                ) }
            for (deleted in deletedPaths) {
                knownHashes.remove(deleted)
                _events.send(FileEvent(agentId, deleted.second, FileEvent.EventType.DELETED, null))
            }
            return
        }

        if (fileOps.isDir(fullPath)) {
            val children = fileOps.listDir(fullPath)

            // Check for deletions within this dir (shallowly, then recursive calls will handle deeper)
            val expectedPrefix = if (path.isEmpty()) "" else "$path/"
            val knownChildren = knownHashes.keys
                .filter { it.first == agentId && it.second.startsWith(expectedPrefix) }
                .map {
                    val relative = it.second.removePrefix(expectedPrefix)
                    relative.substringBefore("/")
                }
                .toSet()

            for (known in knownChildren) {
                if (known !in children && known !in ignores) {
                    // It was deleted
                    reconcile(agentId, expectedPrefix + known)
                }
            }

            for (child in children) {
                if (child in ignores) continue
                reconcile(agentId, expectedPrefix + child)
            }
        } else {
            // It's a file
            val bytes = fileOps.readAllBytes(fullPath)
            val hash = ContentId.of(bytes)
            val prevHash = knownHashes[agentId to path]

            if (prevHash == null) {
                knownHashes[agentId to path] = hash
                _events.send(FileEvent(agentId, path, FileEvent.EventType.CREATED, hash))
            } else if (prevHash != hash) {
                knownHashes[agentId to path] = hash
                _events.send(FileEvent(agentId, path, FileEvent.EventType.MODIFIED, hash))
            }
        }
    }

    suspend fun close() {
        _events.close()
    }
}
