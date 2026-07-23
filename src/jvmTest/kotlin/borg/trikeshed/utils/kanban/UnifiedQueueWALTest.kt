/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
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
}
