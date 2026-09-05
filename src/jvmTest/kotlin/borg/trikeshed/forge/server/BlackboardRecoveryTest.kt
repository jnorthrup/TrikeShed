package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BlackboardRecoveryTest {
    @Test fun revisionsCaptureRapidWritesNullsAndDeletion() {
        val board = ConfixBlackboard()
        repeat(20) { board.put("same", it, "test") }
        val old = board.snapshot()
        board.put("null", null, "test")
        board.remove("same")
        val now = board.snapshot()
        assertEquals(20L, old.revision)
        assertEquals(19, old.values["same"])
        assertEquals(22L, now.revision)
        assertFalse("same" in now.values)
        assertTrue("null" in now.values)
        val replay = board.replay(0)
        assertFalse(replay.reset)
        assertEquals((1L..22L).toList(), replay.changes.map { it.revision })
        assertTrue(replay.changes.last().deleted)
        assertEquals(21L, now.provenance.getValue("null").revision)
    }

    @Test fun staleAndFutureCursorsRequireReset() {
        val board = ConfixBlackboard()
        repeat(300) { board.put("k", it, "test") }
        assertTrue(board.replay(0).reset)
        assertTrue(board.replay(301).reset)
        assertFalse(board.replay(44).reset)
        assertEquals(256, board.replay(44).changes.size)
    }

    @Test fun wireSignalsGapAndNewEpochInsteadOfPretendingContinuity() = runTest {
        val board = ConfixBlackboard()
        repeat(300) { board.put("k", it, "test") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val wire = BlackboardWire(board, scope)
            val chunks = StringBuilder()
            wire.route("GET", "/blackboard/facts?since=0", "") { chunks.append(it.decodeToString()) }
            assertTrue("event: reset" in chunks.toString())
            assertTrue("replay_gap" in chunks.toString())
            chunks.clear()
            wire.route("GET", "/blackboard/facts?since=300&epoch=old", "") { chunks.append(it.decodeToString()) }
            assertTrue("epoch_changed" in chunks.toString())
            val snapshot = JsonSupport.parse(wire.route("GET", "/blackboard/board", "")!!.body) as Map<*, *>
            assertEquals(300L, (snapshot["revision"] as Number).toLong())
            assertEquals(snapshot["revision"], snapshot["seq"])
            assertNotNull(snapshot["epoch"])
        } finally { scope.cancel() }
    }

    @Test fun concurrentSnapshotsNeverPairNewValuesWithOldProvenance() = runBlocking {
        val board = ConfixBlackboard()
        coroutineScope {
            repeat(4) { writer -> launch(Dispatchers.Default) { repeat(100) { board.put("$writer", it, "writer-$writer") } } }
        }
        val snapshot = board.snapshot()
        assertEquals(400L, snapshot.revision)
        assertEquals(4, snapshot.values.size)
        assertTrue(snapshot.provenance.values.all { it.revision <= snapshot.revision })
    }
}
