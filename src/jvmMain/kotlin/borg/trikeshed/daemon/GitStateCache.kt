package borg.trikeshed.daemon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reactive git-state cache — reads `.git` data model directly.
 *
 * No ProcessBuilder. No `git rev-parse`. No `git status`. The daemon's
 * reactive pipeline (JvmFileWatchReactorElement) invalidates cache entries
 * when `.git` files change; consumers read fresh values on the next access.
 *
 * This IS the choreography: the cache is the shared state, file events are
 * the triggers, and the daemon's cycle reads from here instead of spawning
 * git processes.
 *
 * Reading the git data model directly:
 * - HEAD: `.git/HEAD` contains `ref: refs/heads/master\n`; resolve the ref
 *   file for the SHA-1 hex. If HEAD is detached, the file contains the SHA
 *   directly.
 * - packed-refs: if a ref file doesn't exist, the SHA may be in
 *   `.git/packed-refs`.
 * - Working tree clean: compare `.git/index` mtime against the last known
 *   mutation time. The index changes whenever files are staged, committed,
 *   merged, or reset. If the index hasn't changed since we last mutated
 *   the tree, the tree is clean. This is a heuristic — a truly paranoid
 *   check would parse the index binary format and compare file mtimes, but
 *   for the flywheel's purpose (avoiding redundant git status calls) the
 *   mtime heuristic is sufficient.
 */
class GitStateCache(private val repoDir: File) {

    @Volatile private var cachedHeadSha: String? = null
    @Volatile private var headValid: Boolean = false
    @Volatile private var treeValid: Boolean = false
    @Volatile private var cachedTreeClean: Boolean = true
    @Volatile private var lastKnownIndexMtime: Long = 0L

    private val objectsDirtyChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * Read HEAD directly from `.git/HEAD` → ref file.
     * Returns the 40-char hex SHA-1, or empty string if unresolvable.
     */
    suspend fun headSha(): String {
        if (headValid) return cachedHeadSha ?: ""
        val sha = resolveHead()
        cachedHeadSha = sha
        headValid = true
        return sha
    }

    /**
     * Heuristic: is the working tree clean?
     *
     * Compares `.git/index` mtime against the last mtime we recorded
     * after a daemon mutation (commit, merge, etc). If the index hasn't
     * changed since we last touched it, the tree is clean.
     *
     * This replaces `git status --porcelain --untracked-files=no` which
     * spawns a process every cycle. The file watcher invalidates this
     * cache when `.git/index` changes.
     */
    fun isTreeClean(): Boolean {
        if (treeValid) return cachedTreeClean
        val indexFile = File(repoDir, ".git/index")
        val currentIndexMtime = if (indexFile.exists()) indexFile.lastModified() else 0L
        cachedTreeClean = currentIndexMtime == lastKnownIndexMtime
        treeValid = true
        return cachedTreeClean
    }

    /** Called by the daemon after it mutates the tree (commit, merge, etc). */
    fun markTreeMutated() {
        val indexFile = File(repoDir, ".git/index")
        lastKnownIndexMtime = if (indexFile.exists()) indexFile.lastModified() else 0L
        treeValid = false
    }

    /** File watcher saw `.git/HEAD` change — invalidate head cache. */
    fun invalidateHead() {
        headValid = false
    }

    /** File watcher saw `.git/index` change — invalidate tree cache. */
    fun invalidateTree() {
        treeValid = false
    }

    /** File watcher saw new objects — signal the Couch reconcile pipeline. */
    fun markObjectsDirty() {
        objectsDirtyChannel.trySend(Unit)
    }

    /** Suspend until objects are dirty. Consumed by the Couch reconcile coroutine. */
    suspend fun awaitObjectsDirty() {
        objectsDirtyChannel.receive()
    }

    /**
     * Resolve HEAD to a SHA-1 by reading `.git/HEAD` and following the ref.
     * Handles: symbolic refs (`ref: refs/heads/master`), detached HEAD
     * (direct SHA), and packed-refs fallback.
     */
    private suspend fun resolveHead(): String = withContext(Dispatchers.IO) {
        val headFile = File(repoDir, ".git/HEAD")
        if (!headFile.exists()) return@withContext ""
        val headContent = headFile.readText().trim()

        // Detached HEAD: direct SHA
        if (headContent.matches(Regex("[0-9a-f]{40}"))) return@withContext headContent

        // Symbolic ref: `ref: refs/heads/master`
        if (headContent.startsWith("ref: ")) {
            val refPath = headContent.removePrefix("ref: ")
            val refFile = File(repoDir, ".git/$refPath")
            if (refFile.exists()) return@withContext refFile.readText().trim()

            // Fallback: packed-refs
            val packedRefs = File(repoDir, ".git/packed-refs")
            if (packedRefs.exists()) {
                val refLine = packedRefs.readLines().find { it.endsWith(" $refPath") }
                if (refLine != null) return@withContext refLine.substringBefore(" ").trim()
            }
        }
        ""
    }
}
