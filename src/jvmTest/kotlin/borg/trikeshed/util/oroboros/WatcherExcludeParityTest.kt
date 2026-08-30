package borg.trikeshed.util.oroboros

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The operator's daemon spent four hours at 95% CPU and stopped answering HTTP because the
 * worktree watcher's exclude list had drifted from the gateway's. The watcher excluded only
 * `.git`, `.gradle`, `.idea`, `build` and `node_modules`; the gateway also ignores `logs`,
 * `cas`, `.oroboros`, the WALs and the cycle journal. So a write to `logs/oroboros-daemon.log`
 * woke the watcher, the reconcile it triggered printed to that same log, and the loop never
 * ended — the seismic-damping `println` included.
 *
 * The two lists now come from one source. These tests pin that they cannot silently diverge
 * again, and that the fix did not go too far and blind the watcher to real source edits.
 */
class WatcherExcludeParityTest {

    private val glob = PathGlob(emptyList(), WorktreeCouchGateway.watcherExcludeGlobs())

    @Test
    fun everyPathTheReconcileIgnoresIsAlsoIgnoredByTheWatcher() {
        // Watching a path whose events can never change the absorbed set is waste; when the
        // daemon writes that path itself it is a feedback loop.
        for (segment in WorktreeCouchGateway.EXCLUDED_SEGMENTS) {
            assertFalse(glob.accepts(segment), "watcher accepts ignored segment at root: $segment")
            assertFalse(glob.accepts("$segment/inner.txt"), "watcher accepts inside ignored segment: $segment")
            assertFalse(glob.accepts("nested/deep/$segment/inner.txt"), "watcher accepts nested ignored segment: $segment")
        }
        for (prefix in WorktreeCouchGateway.EXCLUDED_RELATIVE_PREFIXES) {
            assertFalse(glob.accepts("$prefix/some/clone/file.kt"), "watcher accepts excluded prefix: $prefix")
        }
    }

    @Test
    fun theDaemonsOwnLogCannotWakeTheWatcher() {
        // The exact path from the wedged daemon's log line:
        //   worktree-quake: N events in 5s (last: MODIFY logs/oroboros-daemon.log)
        assertFalse(glob.accepts("logs/oroboros-daemon.log"))
        assertFalse(glob.accepts("logs/oroboros-8901.log"))
        assertFalse(glob.accepts("oroboros-cycles.jsonl"))
        assertFalse(glob.accepts("jules-board.wal"))
        assertFalse(glob.accepts("brain-errors.jsonl"))
        assertFalse(glob.accepts(".causal.wal"))
        assertFalse(glob.accepts("cas/sha256/ab/cdef"))
        assertFalse(glob.accepts(".oroboros/manifests/classpath.tsv"))
        // The spill that followed the first fix: a build run beside the daemon creates and
        // deletes this file around every compile, and each transition fired a full reconcile.
        assertFalse(glob.accepts(".kotlin/sessions/kotlin-compiler-4398423614898389958.salive"))
        assertFalse(glob.accepts(".kotlin/errors/errors-123.log"))
    }

    @Test
    fun realSourceAndDocumentEditsStillWakeTheWatcher() {
        // A watcher that excludes too much is a quieter bug, not a fixed one.
        for (path in listOf(
            "src/commonMain/kotlin/borg/trikeshed/util/oroboros/WorktreeCouchGateway.kt",
            "README.md",
            "doc/todo.md",
            "bin/oroboros-daemon",
            "src/commonMain/resources/web/kanban.html",
            // Not the excluded `build` segment, and not `logs`: names that merely start the same.
            "buildSrc/Deps.kt",
            "logsmith/notes.md",
        )) {
            assertTrue(glob.accepts(path), "watcher went blind to a real edit: $path")
        }
    }
}

/**
 * The walk had its OWN third exclude list — `.gradle`, `.idea`, `build`, `node_modules` — which
 * omitted `.git` and `.claude/worktrees`. So the worktree watcher registered an OS watch on every
 * directory of the git object store and of every agent worktree checkout: minutes of walking at
 * boot and a permanent watch set, for events the glob filter then threw away.
 *
 * It is per-instance now, because instances genuinely disagree: the git watcher exists to see
 * everything under `.git`, which the worktree watcher must never descend into.
 */
class WalkerPruningTest {

    private fun watcher(root: java.io.File) = borg.trikeshed.util.oroboros.JvmFileWatchReactorElement(
        root = root.absolutePath,
        includeGlobs = emptyList(),
        excludeGlobs = WorktreeCouchGateway.watcherExcludeGlobs(),
        walkerBlockedSegments = WorktreeCouchGateway.EXCLUDED_SEGMENTS,
        walkerBlockedRelativePrefixes = WorktreeCouchGateway.EXCLUDED_RELATIVE_PREFIXES,
    )

    @kotlin.test.Test
    fun theWorktreeWatcherPrunesGitAndAgentWorktreesFromTheWalk() {
        val root = java.nio.file.Files.createTempDirectory("walk-prune-").toFile()
        try {
            val w = watcher(root)
            fun p(rel: String) = java.nio.file.Path.of(root.absolutePath, *rel.split("/").toTypedArray())
            // The two the old global list missed, and which dominated the cost.
            assertTrue(w.isIgnored(p(".git")), ".git must not be walked by the worktree watcher")
            assertTrue(w.isIgnored(p(".git/objects/ab")))
            assertTrue(w.isIgnored(p(".claude/worktrees")))
            assertTrue(w.isIgnored(p(".claude/worktrees/agent-a1/src/commonMain")))
            // And the daemon-state paths, so the walk matches the event filter.
            assertTrue(w.isIgnored(p("logs")))
            assertTrue(w.isIgnored(p("cas/sha256/ab")))
            assertTrue(w.isIgnored(p(".oroboros/manifests")))
            assertTrue(w.isIgnored(p("build/live/classes")))
            // Real trees must still be walked, including `.claude` itself — only its
            // `worktrees` subtree is other people's checkouts.
            assertFalse(w.isIgnored(p("src/commonMain/kotlin")))
            assertFalse(w.isIgnored(p("doc")))
            assertFalse(w.isIgnored(p(".claude")))
            assertFalse(w.isIgnored(p(".claude/skills")))
        } finally {
            root.deleteRecursively()
        }
    }

    @kotlin.test.Test
    fun theGitWatcherStillDescendsIntoGitOnTheDefaults() {
        val root = java.nio.file.Files.createTempDirectory("walk-git-").toFile()
        try {
            // Defaults: what the git-side watcher uses. Blocking .git globally would have made
            // this watcher useless, which is why the pruning list is per-instance.
            val w = borg.trikeshed.util.oroboros.JvmFileWatchReactorElement(root = root.absolutePath)
            assertFalse(w.isIgnored(java.nio.file.Path.of(root.absolutePath, ".git", "objects")))
            assertTrue(w.isIgnored(java.nio.file.Path.of(root.absolutePath, "build")))
        } finally {
            root.deleteRecursively()
        }
    }
}
