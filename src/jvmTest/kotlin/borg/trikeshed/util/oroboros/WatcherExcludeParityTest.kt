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
