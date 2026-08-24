package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Occupy a git repo: point the daemon at ANY path with a `.git` (worktree checkout or ordinary
 * clone — both are accepted, matching `git worktree`'s own `.git` FILE-vs-DIR duality), absorb its
 * worktree into the store under `repos/<id>/`, then keep watching it live — the same shape as the
 * primary repo's own worktree plane, generalized to be started and stopped at runtime instead of
 * baked into daemon startup. The git OBJECT plane ([GitCouchGateway]) stays out for now: it is
 * `.git/`-prefix-only (unparametrized), so a second occupied repo would collide with the first —
 * worktree content is what "agglomerate the filewatcher" actually needs; object-plane parity for
 * N repos is a follow-up, not silently done wrong.
 */
class RepoOccupancy private constructor(
    val id: String,
    val repoPath: String,
    val prefix: String,
    private val watcher: JvmFileWatchReactorElement,
    private val watchJob: Job,
    private val supervisor: Job,
) {
    @Volatile var lastPaths: Int = 0; private set
    @Volatile var lastReconcileMs: Long = 0; private set
    @Volatile var alive: Boolean = true; private set

    internal fun noted(count: Int) {
        lastPaths = count
        lastReconcileMs = System.currentTimeMillis()
    }

    suspend fun stop() {
        alive = false
        watchJob.cancel()
        runCatching { watcher.close() }
        supervisor.cancel()
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "path" to repoPath, "prefix" to prefix,
        "paths" to lastPaths, "lastReconcileMs" to lastReconcileMs, "watching" to alive,
    )

    companion object {
        private val EXCLUDED = setOf(".git", ".gradle", ".idea", "build", "node_modules")

        /** True for both an ordinary clone (`.git/` directory) and a `git worktree` checkout (`.git` file). */
        fun looksLikeGitRepo(path: File): Boolean = path.isDirectory && File(path, ".git").exists()

        fun idFor(path: File, taken: Set<String>): String {
            val base = path.name.ifBlank { "repo" }.replace(Regex("[^\\w.-]+"), "_")
            if (base !in taken) return base
            val salt = kotlin.math.abs(path.absolutePath.hashCode()).toString(16).take(6)
            return "$base-$salt"
        }

        /** Validate, run the initial reconcile, and start live watching. Throws on a bad path. */
        suspend fun occupy(path: File, id: String, attachmentGateway: CouchAttachmentGateway, parentJob: Job?): RepoOccupancy {
            require(looksLikeGitRepo(path)) { "not a git repo (no .git): ${path.absolutePath}" }
            val prefix = "repos/$id/"
            val fileOps = JvmFileOperations()
            val gateway = WorktreeCouchGateway(fileOps, attachmentGateway, prefix = prefix, excludedSegments = EXCLUDED)

            val supervisor = SupervisorJob(parentJob)
            val scope = CoroutineScope(supervisor + Dispatchers.IO)

            val initial = gateway.reconcile(path.absolutePath, "oroboros-occupy", "occupy-$id", System.currentTimeMillis())

            val watcher = JvmFileWatchReactorElement(
                root = path.absolutePath,
                parentJob = supervisor,
                includeGlobs = emptyList(),
                excludeGlobs = listOf(".git/**", ".gradle/**", ".idea/**", "build/**", "node_modules/**"),
            )
            scope.launch { watcher.open() }

            // One instance, built before the watch loop starts, so both the initial reconcile and
            // every live re-absorption update the SAME object — no discarded twin, no race.
            lateinit var occupancy: RepoOccupancy
            val watchJob = scope.launch {
                var pending = false
                while (true) {
                    val event = watcher.events.receiveCatching().getOrNull() ?: break
                    pending = true
                    // Coalesce a burst (a git checkout / IDE save storm) into one reconcile — the
                    // same 250ms debounce the primary repo's own worktree watcher uses.
                    while (pending) {
                        pending = false
                        delay(250)
                        if (watcher.events.tryReceive().isSuccess) pending = true
                    }
                    runCatching {
                        val snap = gateway.reconcile(path.absolutePath, "oroboros-occupy", "occupy-$id-live", System.currentTimeMillis())
                        occupancy.noted(snap.paths.size)
                    }
                }
            }
            occupancy = RepoOccupancy(id, path.absolutePath, prefix, watcher, watchJob, supervisor)
            occupancy.noted(initial.paths.size)
            return occupancy
        }
    }
}
