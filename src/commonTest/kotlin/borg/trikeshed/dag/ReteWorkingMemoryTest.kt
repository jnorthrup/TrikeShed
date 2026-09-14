package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.iterator
import borg.trikeshed.lib.map
import borg.trikeshed.lib.view

class ReteWorkingMemoryTest {

    @Test
    fun assertModifyRetractByStableFactIdAndVersionCid() = kotlinx.coroutines.test.runTest {
        val memory = ReteWorkingMemory()
        val board = blackboardContext(id = "board-a")
        val factId = FactId("board-a", "node-1")
        val firstCid = ContentId.of("v1".encodeToByteArray())

        val asserted = memory.assert(
            factId,
            mapOf("status" to "ready"),
            firstCid,
            board,
        )
        assertTrue(asserted.isNew)
        assertEquals("ready", memory.facts(factId).view.single().fields["status"])
        assertEquals(firstCid, memory.facts(factId).view.single().versionCid)

        val secondCid = ContentId.of("v2".encodeToByteArray())
        memory.modify(FactId("board-a", "node-1"), mapOf("status" to "active"), secondCid)
        assertEquals(1, memory.facts(factId).size, "modify replaces the current version")
        assertEquals("active", memory.facts(factId).view.single().fields["status"])
        assertEquals(secondCid, memory.facts(factId).view.single().versionCid)

        assertTrue(memory.retract(FactId("board-a", "node-1")))
        assertEquals(0, memory.facts(factId).size)
    }

    @Test
    fun duplicateFactVersionIsNotAssertedTwice() = kotlinx.coroutines.test.runTest {
        val memory = ReteWorkingMemory()
        val board = blackboardContext(id = "board-a")
        val factId = FactId("board-a", "node-1")
        val cid = ContentId.of("v1".encodeToByteArray())

        assertTrue(memory.assert(factId, mapOf("status" to "ready"), cid, board).isNew)
        assertFalse(memory.assert(FactId("board-a", "node-1"), mapOf("status" to "ready"), cid, board).isNew)
        assertEquals(1, memory.facts(factId).size)
    }

    @Test
    fun facetQueriesStayInsideBoardPartition() = kotlinx.coroutines.test.runTest {
        val memory = ReteWorkingMemory()
        val boardA = blackboardContext(id = "board-a")
        val boardB = blackboardContext(id = "board-b")

        memory.assert(
            FactId("board-a", "node-1"),
            mapOf("status" to "ready"),
            ContentId.of("a".encodeToByteArray()),
            boardA,
        )
        memory.assert(
            FactId("board-b", "node-2"),
            mapOf("status" to "ready"),
            ContentId.of("b".encodeToByteArray()),
            boardB,
        )

        assertEquals(listOf("board-a" to "node-1"),
            memory.query(boardA, "status" j "ready").map { it.factId.pair })
        assertEquals(listOf("board-b" to "node-2"),
            memory.query(boardB, "status" j "ready").map { it.factId.pair })
    }
}
