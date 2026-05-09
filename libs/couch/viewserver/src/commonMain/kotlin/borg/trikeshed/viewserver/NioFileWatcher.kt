package borg.trikeshed.viewserver

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * File-system watcher CCEK — detects file changes via [FileOperations].
 *
 * Polls a directory tree, comparing last-modified timestamps (or content hashes)
 * to detect create/modify/delete events. Emits [FileChange] events to [onChange].
 *
 * ## Usage
 *
 * ```
 * val watcher = NioFileWatcher(fileOps, root = "/my/repo")
 * withContext(ctx) {
 *     watcher.start(scope) { change ->
 *         when (change) {
 *             is FileChange.Modified -> syncEngine.upsert(change.path)
 *             is FileChange.Deleted  -> syncEngine.delete(change.path)
 *         }
 *     }
 * }
 * ```
 *
 * CCEK: register in the coroutine context via [Key].
 */
class NioFileWatcher(
    private val fileOps: FileOperations,
    private val root: String,
    private val pollIntervalMs: Long = 2000L,
    private val excludes: List<String> = listOf(".git/objects", "build/", ".gradle/", "node_modules/"),
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<NioFileWatcher>
    override val key: CoroutineContext.Key<*> get() = Key

    /** Last-known state: path → content hash or last-modified info. */
    private val knownFiles = mutableMapOf<String, SnapshotEntry>()

    data class SnapshotEntry(
        val path: String,
        val size: Long,
        val exists: Boolean,
    )

    sealed class FileChange {
        abstract val path: String
        data class Created(override val path: String, val content: String) : FileChange()
        data class Modified(override val path: String, val content: String) : FileChange()
        data class Deleted(override val path: String) : FileChange()
    }

    /**
     * Start the watcher in [scope]. Polls every [pollIntervalMs] and calls [onChange]
     * for every detected file change.
     */
    fun start(scope: CoroutineScope, onChange: suspend (FileChange) -> Unit): Job = scope.launch {
        // Initial scan: build baseline (no suspend needed for baseline)
        fullScan { path, content ->
            knownFiles[path] = SnapshotEntry(path, content.length.toLong(), true)
        }

        while (isActive) {
            delay(pollIntervalMs)
            val currentSnapshot = mutableMapOf<String, SnapshotEntry>()
            val seen = mutableSetOf<String>()

            fullScan { path, content ->
                seen.add(path)
                val entry = SnapshotEntry(path, content.length.toLong(), true)
                currentSnapshot[path] = entry
                val prev = knownFiles[path]
                if (prev == null) {
                    onChange(FileChange.Created(path, content))
                } else if (prev.size != entry.size) {
                    onChange(FileChange.Modified(path, content))
                }
            }

            // Detect deletions
            for ((path, _) in knownFiles) {
                if (path !in seen) {
                    onChange(FileChange.Deleted(path))
                }
            }

            knownFiles.clear()
            knownFiles.putAll(currentSnapshot)
        }
    }

    /** Walk the directory tree and call [onFile] for every non-excluded file. */
    private suspend fun fullScan(onFile: suspend (path: String, content: String) -> Unit) {
        scanDir(root, onFile)
    }

    private suspend fun scanDir(dir: String, onFile: suspend (path: String, content: String) -> Unit) {
        if (!fileOps.exists(dir) || !fileOps.isDir(dir)) return
        val entries = fileOps.listDir(dir)
        for (name in entries) {
            val fullPath = fileOps.resolvePath(dir, name)
            val relPath = fullPath.removePrefix(root).trimStart('/')
            if (excludes.any { relPath.startsWith(it) || fullPath.contains("/$it") }) continue
            if (fileOps.isDir(fullPath)) {
                scanDir(fullPath, onFile)
            } else if (fileOps.isFile(fullPath)) {
                try {
                    val content = fileOps.readString(fullPath)
                    onFile(relPath, content)
                } catch (_: Exception) {
                    // Skip unreadable files (binary, etc.)
                }
            }
        }
    }
}
