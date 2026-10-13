package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import borg.trikeshed.lib.*

class ReteWorkingMemoryTest {

    @Test
    fun partitionLifecycleRetainsIndependentIdentitiesAndFrozenReads() = kotlinx.coroutines.test.runTest {
        val memory = ReteWorkingMemory()
        val cid = ContentId.of("v1".encodeToByteArray())
        for (partition in listOf("z", "a", "metadata")) {
            val board = blackboardContext(id = partition)
            memory.assert(FactId(partition, "same-id"), mapOf("status" to "ready"), cid, board)
        }
        val before = memory.all()
        assertEquals(listOf("a", "metadata", "z"), before.view.map { it.factId.a })
        assertEquals(listOf("a", "z"), memory.all(s_["missing", "z", "a", "z"]).view.map { it.factId.a })
        assertTrue(memory.query(blackboardContext(id = "missing"), "status" j "ready").isEmpty())
        assertFailsWith<IllegalStateException> {
            memory.modify(FactId("missing", "same-id"), emptyMap(), cid)
        }
        assertFailsWith<IllegalArgumentException> {
            memory.assert(FactId("a", "same-id"), mapOf("status" to "changed"), cid, blackboardContext(id = "a"))
        }
        assertFailsWith<IllegalArgumentException> {
            memory.assert(FactId("wrong", "same-id"), emptyMap(), cid, blackboardContext(id = "a"))
        }
        assertTrue(memory.retract(FactId("a", "same-id")))
        assertFalse(memory.retract(FactId("a", "same-id")))
        assertEquals(2, memory.all().size)
        memory.assert(FactId("a", "same-id"), mapOf("status" to "active"), cid, blackboardContext(id = "a"))
        assertEquals(listOf("a", "metadata", "z"), memory.all().view.map { it.factId.a })
        assertTrue(before.view.all { it.fields["status"] == "ready" })
        for (partition in listOf("a", "metadata", "z")) assertTrue(memory.retract(FactId(partition, "same-id")))
        assertTrue(memory.all().isEmpty())
        assertTrue(memory.all(s_["a", "z"]).isEmpty())
    }

    @Test
    fun partitionSnapshotRetainsOrderAndVersion() = kotlinx.coroutines.test.runTest {
        val network = ReteNetwork()
        for (partition in listOf("panels", "metadata", "graal")) {
            val board = blackboardContext(id = partition)
            for (id in listOf("z", "a")) network.assert(FactId(partition, id),
                mapOf("value" to 1), ContentId.of("$partition/$id/1".encodeToByteArray()), board)
        }
        val selected = network.snapshot(s_["panels", "graal", "panels"])
        assertEquals(listOf("graal" to "a", "graal" to "z", "panels" to "a", "panels" to "z"),
            selected.view.map { it.factId.pair })
        network.modify(FactId("panels", "a"), mapOf("value" to 2), ContentId.of("v2".encodeToByteArray()))
        network.retract(FactId("graal", "z"))
        assertTrue(selected.view.all { it.fields["value"] == 1 })
        assertEquals(4, selected.size, "a read retains its versions after subsequent writes")
        assertEquals(3, network.snapshot(s_["panels", "graal"]).size)
        assertEquals(5, network.snapshot().size, "unselected metadata remains in the full snapshot")
        assertEquals(0, network.snapshot(emptySeriesOf()).size)
    }

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
