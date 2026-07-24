package borg.trikeshed.utils.kanban

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip the unified queue through the WAL. The board store is the truth —
 * jules URLs, kanban columns, and the queue projection are all
 * projections of this log.
 */
class UnifiedQueueWALTest {

    private fun tempStore(): Pair<JulesBoardStore, File> {
        val dir = Files.createTempDirectory("unified-queue-test").toFile()
        val walFile = File(dir, "test-board.wal")
        val store = JulesBoardStore(JvmAppendWal(walFile))
        return store to walFile
    }

    @Test
    fun workQueuedRoundTripsThroughWal() = runBlocking {
        val (store, dir) = tempStore()
        try {
            store.appendWork(
                workId = "w-1",
                cause = JulesCause.WorkQueued(
                    workId = "w-1",
                    tier = "feature",
                    title = "Test work item",
                    spec = "Implement X",
                    parent = "parent-1",
                    score = 0.7,
                    at = 1000L,
                )
            )

            val queue = store.loadQueue()
            assertEquals(1, queue.size)
            val entry = queue[0]
            assertEquals("w-1", entry.workId)
            assertEquals("feature", entry.tier)
            assertEquals("Test work item", entry.title)
            assertEquals("Implement X", entry.spec)
            assertEquals("parent-1", entry.parent)
            assertEquals(0.7, entry.score, 1e-9)
            assertEquals(1000L, entry.queuedAt)
            assertNull(entry.sessionId)
            assertTrue(!entry.isDispatched)
            assertTrue(!entry.isDrained)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun dispatchAndDrainTransitionIsolatesFromStateJson() = runBlocking {
        val (store, dir) = tempStore()
        try {
            // enqueue
            store.appendWork("w-2", JulesCause.WorkQueued(
                workId = "w-2", tier = "task", title = "T2",
                spec = "do it", at = 100L,
            ))
            // dispatch → jules session
            store.appendWork("w-2", JulesCause.WorkDispatched(
                workId = "w-2", sessionId = "sess-abc", attempt = 1, at = 200L,
            ))
            // drain → merged
            store.appendWork("w-2", JulesCause.WorkDrained(
                workId = "w-2", sessionId = "sess-abc",
                commitSha = "deadbeef", taskId = "abc-task-id",
                receipt = MergeReceipt(
                    workId = "w-2",
                    producer = "jules",
                    producerRef = "sess-abc",
                    patchCid = ContentId("sha256:" + "0".repeat(64)),
                    revision = "deadbeef",
                    versionTag = "flywheel/jules-sess-abc-deadbeef",
                    lexicalMemory = LexicalMemory(
                        summary = "Fix queue drain",
                        title = "Queue receipt",
                        content = "retain immutable patch claim",
                    ),
                    claimedAt = 299L,
                ),
                at = 300L,
            ))

            val queue = store.loadQueue()
            assertEquals(1, queue.size)
            val entry = queue[0]
            assertEquals("w-2", entry.workId)
            assertEquals("sess-abc", entry.sessionId)
            assertEquals(1, entry.attempt)
            assertEquals(200L, entry.dispatchedAt)
            assertEquals("deadbeef", entry.commitSha)
            assertEquals("abc-task-id", entry.taskId)
            assertEquals("flywheel/jules-sess-abc-deadbeef", entry.receipt?.versionTag)
            assertEquals("sha256:" + "0".repeat(64), entry.receipt?.patchCid?.value)
            assertEquals("Queue receipt", entry.receipt?.lexicalMemory?.title)
            assertEquals(300L, entry.drainedAt)
            assertTrue(entry.isDispatched)
            assertTrue(entry.isDrained)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun receiptPrUrlRoundTripsThroughWal() = runBlocking {
        // The receipt's prUrl is optional but, when set, must survive the
        // WAL encode/decode round trip — otherwise the receipt persisted on
        // disk loses its tie to the upstream PR/branch surface.
        val (store, dir) = tempStore()
        try {
            val prUrl = "https://github.com/jnorthrup/TrikeShed/commit/abc123def456abc123def456abc123def456abcd"
            // loadQueue folds WorkDrained onto a pre-existing WorkQueued key
            // (JulesBoardStore.loadQueue:127-141); mirror the production pattern.
            store.appendWork("w-4", JulesCause.WorkQueued(
                workId = "w-4", tier = "feature", title = "PR bridge",
                spec = "do it", at = 900L,
            ))
            store.appendWork("w-4", JulesCause.WorkDrained(
                workId = "w-4", sessionId = "sess-4",
                commitSha = "abc123def456abc123def456abc123def456abcd",
                taskId = "abc-task",
                receipt = MergeReceipt(
                    workId = "w-4",
                    producer = "jules",
                    producerRef = "sess-4",
                    patchCid = ContentId("sha256:" + "a".repeat(64)),
                    revision = "abc123def456abc123def456abc123def456abcd",
                    versionTag = "flywheel/jules-sess-4-abc123def456",
                    lexicalMemory = LexicalMemory(
                        summary = "PR-bridge", title = "t", content = "c",
                    ),
                    claimedAt = 999L,
                    prUrl = prUrl,
                ),
                at = 1000L,
            ))
            val r = store.loadQueue().single().receipt
            assertNotNull(r)
            assertEquals(prUrl, r.prUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun receiptWithoutPrUrlSurvivesAsNull() = runBlocking {
        // Negative case: a receipt with no upstream PR (Jules pushed a branch
        // but no PR was opened) must round-trip with prUrl == null, not empty string.
        val (store, dir) = tempStore()
        try {
            store.appendWork("w-5", JulesCause.WorkQueued(
                workId = "w-5", tier = "feature", title = "no PR",
                spec = "do it", at = 1L,
            ))
            store.appendWork("w-5", JulesCause.WorkDrained(
                workId = "w-5", sessionId = "sess-5",
                commitSha = "feedface",
                taskId = "t5",
                receipt = MergeReceipt(
                    workId = "w-5",
                    producer = "jules",
                    producerRef = "sess-5",
                    patchCid = ContentId("sha256:" + "b".repeat(64)),
                    revision = "feedface",
                    versionTag = "flywheel/jules-sess-5-feedface",
                    lexicalMemory = LexicalMemory(summary = "s", title = "t", content = "c"),
                    claimedAt = 1L,
                ),
                at = 2L,
            ))
            assertNull(store.loadQueue().single().receipt?.prUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun queueAndBoardCoexistOnSameWal() = runBlocking {
        val (store, dir) = tempStore()
        try {
            // Queue a work item
            store.appendWork("w-3", JulesCause.WorkQueued(
                workId = "w-3", tier = "task", title = "T3",
                spec = "spec", at = 50L,
            ))
            // Dispatch it
            store.appendWork("w-3", JulesCause.WorkDispatched(
                workId = "w-3", sessionId = "sess-3", attempt = 1, at = 100L,
            ))
            // Now record jules activity for that session
            val snap = borg.trikeshed.jules.JulesSnapshot(
                sessionId = "sess-3",
                state = "IN_PROGRESS",
                title = "T3",
                patchBytes = 0L,
                headSha = "abc",
                activeCount = 1,
                awaitingCount = 0,
                capturedAt = 200L,
            )
            store.append(snap, drained = false, cause = JulesCause.StateObserved("QUEUED", "IN_PROGRESS", 200L))

            // Both projections work from the same WAL
            val queue = store.loadQueue()
            val board = store.load()

            assertEquals(1, queue.size)
            assertEquals("w-3", queue[0].workId)
            assertEquals("sess-3", queue[0].sessionId)

            assertEquals(1, board.size)
            val card = board["sess-3"]
            assertNotNull(card)
            assertEquals("IN_PROGRESS", card.snapshot.state)
            assertEquals(1, card.causes.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun receiptlessDrainIsProjectedAsUnclaimed() = runBlocking {
        val (store, dir) = tempStore()
        try {
            store.appendWork("w-unclaimed", JulesCause.WorkQueued(
                workId = "w-unclaimed", tier = "task", title = "Unclaimed",
                spec = "must block settlement", at = 100L,
            ))
            store.appendWork("w-unclaimed", JulesCause.WorkDrained(
                workId = "w-unclaimed", sessionId = "sess-unclaimed",
                commitSha = "deadbeef", taskId = "unclaimed-task",
                receipt = null,
                at = 200L,
            ))

            val entry = store.loadQueue().single()
            assertTrue(entry.isDrained)
            assertTrue(entry.isUnclaimedDrain)
            assertNull(entry.receipt)
        } finally {
            dir.deleteRecursively()
        }
    }
}
