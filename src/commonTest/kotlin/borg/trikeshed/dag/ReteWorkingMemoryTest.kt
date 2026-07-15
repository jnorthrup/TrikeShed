package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReteWorkingMemoryTest {

    @Test
    fun assertModifyRetractByStableFactIdAndVersionCid() {
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
        assertEquals("ready", memory.facts(factId).single().fields["status"])
        assertEquals(firstCid, memory.facts(factId).single().versionCid)

        val secondCid = ContentId.of("v2".encodeToByteArray())
        memory.modify(factId, mapOf("status" to "active"), secondCid)
        assertEquals(1, memory.facts(factId).size, "modify replaces the current version")
        assertEquals("active", memory.facts(factId).single().fields["status"])
        assertEquals(secondCid, memory.facts(factId).single().versionCid)

        assertTrue(memory.retract(factId))
        assertEquals(emptyList(), memory.facts(factId))
    }

    @Test
    fun duplicateFactVersionIsNotAssertedTwice() {
        val memory = ReteWorkingMemory()
        val board = blackboardContext(id = "board-a")
        val factId = FactId("board-a", "node-1")
        val cid = ContentId.of("v1".encodeToByteArray())

        assertTrue(memory.assert(factId, mapOf("status" to "ready"), cid, board).isNew)
        assertFalse(memory.assert(factId, mapOf("status" to "ready"), cid, board).isNew)
        assertEquals(1, memory.facts(factId).size)
    }

    @Test
    fun facetQueriesStayInsideBoardPartition() {
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

        assertEquals(listOf(FactId("board-a", "node-1")),
            memory.query(boardA, "status" to "ready").map { it.factId })
        assertEquals(listOf(FactId("board-b", "node-2")),
            memory.query(boardB, "status" to "ready").map { it.factId })
    }
}
