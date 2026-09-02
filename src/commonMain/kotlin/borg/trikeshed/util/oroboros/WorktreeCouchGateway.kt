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
    /**
     * Files larger than this are not documents and are never read. An 8 GB Blu-ray remux that
     * a download dropped into the repo root (untracked, gitignored, 2026-09-01) made
     * `readAllBytes` throw `OutOfMemoryError: Required array size too large` at every boot and
     * the whole reconcile died with it — the plane held git refs alone. A JVM array tops out
     * at 2 GB; a source tree's largest legitimate document is orders of magnitude smaller.
     * The size is a stat, taken before the read, so the file is never opened for content.
     */
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {
    data class Snapshot(
        val revision: String,
        val paths: List<String>,
        val deletedPaths: List<String> = emptyList(),
        /**
         * Directories the walk could not enumerate. A non-empty list means this snapshot is
         * INCOMPLETE — callers log it rather than reading `paths` as the whole tree, because a
         * partial plane that reports success is the failure mode this field exists to expose.
         */
        val skippedDirs: List<String> = emptyList(),
        /** Files over [maxFileBytes], by relative path: present in the tree, absent from the plane, said so. */
        val skippedFiles: List<String> = emptyList(),
    )

    fun reconcile(
        repoRoot: String,
        agentId: String,
        revision: String,
        sequence: Long,
    ): Snapshot {
        if (!fileOps.isDir(repoRoot)) return Snapshot(revision, emptyList())

        val skippedDirs = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()
        val current = collectFiles(repoRoot, skippedDirs)
        val existing = attachments.listAttachments(prefix).associateBy { it.path }

        for ((relativePath, physicalPath) in current) {
            val logicalPath = prefix + relativePath
            // Stat before read: an oversized file is skipped and NAMED, never loaded.
            if (sizeOf(physicalPath) > maxFileBytes) { skippedFiles.add(relativePath); continue }
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

        // An oversized file is in the tree but not in the plane: it is neither claimed as a path
        // nor protected from tombstoning — a file that GREW past the cap leaves the plane.
        val currentLogicalPaths = current.keys.filterNot { it in skippedFiles }.mapTo(mutableSetOf()) { prefix + it }
        val deletedPaths = mutableListOf<String>()
        // A path missing from `current` because its directory could not be ENUMERATED is not a
        // deleted file — tombstoning it would convert a transient read failure into real data
        // loss, and the next pass would re-absorb it, churning the store forever. Absence is
        // only evidence of deletion under a subtree the walk actually reached.
        val unreadable = skippedDirs.map { if (it.isEmpty()) prefix else "$prefix$it/" }
        for ((path, ref) in existing) {
            if (path in currentLogicalPaths) continue
            if (unreadable.any { path.startsWith(it) }) continue
            attachments.deleteAttachment(path, ref.revision)
            deletedPaths.add(path)
        }

        return Snapshot(revision, currentLogicalPaths.sorted(), deletedPaths.sorted(), skippedDirs.sorted(), skippedFiles.sorted())
    }

    /** A stat, not a read: open, size, close. -1 when it cannot be told, and then the read decides. */
    private fun sizeOf(path: String): Long {
        val fd = try { fileOps.open(path) } catch (_: Exception) { return -1L }
        return try { fileOps.size(fd) } finally { fileOps.close(fd) }
    }

    private fun collectFiles(repoRoot: String, skippedDirs: MutableList<String>): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val queue = mutableListOf(repoRoot to "")
        while (queue.isNotEmpty()) {
            val (directory, relativeDirectory) = queue.removeAt(0)
            // One unreadable directory is one subtree lost, not the whole worktree. Before this
            // guard a single throw from the walk (an undecodable filename, a permission change
            // mid-pass) propagated out of reconcile() and left the doc plane holding git refs
            // alone — the failure looked like "no worktree documents exist" rather than an error.
            val names = try {
                fileOps.listDir(directory).sorted()
            } catch (_: Exception) {
                skippedDirs.add(relativeDirectory); continue
            }
            for (name in names) {
                if (name in excludedSegments) continue
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                if (excludedRelativePrefixes.any { relative == it || relative.startsWith("$it/") }) continue
                try {
                    val fullPath = fileOps.resolvePath(directory, name)
                    if (fileOps.isDir(fullPath)) {
                        queue.add(fullPath to relative)
                    } else if (fileOps.isFile(fullPath)) {
                        files[relative] = fullPath
                    }
                } catch (_: Exception) {
                    skippedDirs.add(relative)
                }
            }
        }
        return files
    }

    companion object {
        /**
         * Default logical prefix for the primary worktree gateway. Not a `const val`: a whole
         * separate daemon PROCESS rooted at a different repo (its own `--kanban-port` and
         * `forgeHome`, run standalone rather than absorbed via a single daemon's occupied-repo
         * registry) sets this once at boot from its own `repoDir.name`, before any gateway is
         * constructed — every default-prefix consumer (this class, [MemoryBridge],
         * `ClasspathSourceProjection`, `CouchWireRouter`) then keys off that instance's own
         * project name instead of a name borrowed from this one.
         */
        var WORKTREE_PREFIX = "projects/trikeshed/"

        /** 64 MiB. Above this a worktree file is a payload, not a document, and is skipped by name. */
        const val MAX_FILE_BYTES: Long = 64L shl 20

        val EXCLUDED_SEGMENTS = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules",
            // Daemon-state turds must never enter the doc plane: a stray `cas/` in a
            // worktree once snowballed self-referentially (absorbing its own output,
            // 108k blobs / 536MB in minutes). State lives in forge homes, period.
            "cas", ".oroboros", ".causal.wal", "oroboros-cycles.jsonl",
            "jules-board.wal", "brain-errors.jsonl",
            // The daemon's own rolling logs: absorbing them re-quakes the watcher on
            // every log line — a permanent self-reconcile loop churning store sequence.
            "logs",
            // Kotlin's build-session directory. `.kotlin/sessions/kotlin-compiler-<n>.salive`
            // is created and deleted around every compile, so a build the operator runs beside
            // the daemon toggles one tracked path and fires a FULL reconcile per transition —
            // measured live at 4168 ↔ 4169 paths on a loop, holding the daemon at 53.6% CPU.
            // Same shape as `logs` and `build`: a tool's own churn directory, never content.
            ".kotlin",
        )

        /** Agent worktree clones are other checkouts, not this project's history. */
        val EXCLUDED_RELATIVE_PREFIXES = setOf(".claude/worktrees")

        /**
         * Exclude globs for a file watcher driving this gateway, derived from the SAME sets the
         * walk ignores so the two cannot drift.
         *
         * They had drifted, and it cost the operator's daemon: the watcher excluded only
         * `.git`, `.gradle`, `.idea`, `build` and `node_modules`, so every write to
         * `logs/oroboros-daemon.log` woke it, and the reconcile it triggered printed to that
         * same log. The daemon fed its own watcher — `worktree-quake: N events in 5s (last:
         * MODIFY logs/oroboros-daemon.log)` forever, at 95% CPU, until it stopped answering
         * HTTP altogether. The seismic-damping `println` was part of the cycle, so the thing
         * meant to quiet the log was keeping the loop alive.
         *
         * Watching a path whose events can never change what gets absorbed is waste at best;
         * when the daemon itself writes that path it is a feedback loop. Both cases are ruled
         * out by construction here. `EXCLUDED_SEGMENTS` matches at ANY depth in [collectFiles],
         * so each entry becomes four globs — the bare name and the subtree, at the root and
         * nested — because the matcher full-matches each pattern against the relative path.
         */
        fun watcherExcludeGlobs(): List<String> = buildList {
            for (segment in EXCLUDED_SEGMENTS) {
                add(segment); add("$segment/**"); add("**/$segment"); add("**/$segment/**")
            }
            for (prefix in EXCLUDED_RELATIVE_PREFIXES) {
                add(prefix); add("$prefix/**")
            }
        }
    }
}
