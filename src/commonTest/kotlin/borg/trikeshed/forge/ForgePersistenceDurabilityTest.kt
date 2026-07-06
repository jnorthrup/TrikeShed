package borg.trikeshed.forge

import borg.trikeshed.blackboard.BlackboardSurfaceProjectionTest
import borg.trikeshed.ccek.CCEK
import borg.trikeshed.dag.ReteAgentTest
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.kanban.ForgeBoardFSMTest
import borg.trikeshed.lcnc.reduction.LcncReductionCoreTest
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class ForgePersistenceDurabilityTest {

    @Test fun mutationProducesImmediateWalEntry() {
        val store = InMemoryForgePersistenceStore()
        val persistence = ForgePersistenceCoordinator(store)
        val first = workspaceSnapshot(1)

        val sizeAfterFirst = persistence.recordMutation(first, walEntry(1, first))
        assertEquals(1, sizeAfterFirst)
        val envelopeAfterFirst = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelopeAfterFirst)
        assertEquals(1, envelopeAfterFirst.wal.size)
        assertEquals("n-1", envelopeAfterFirst.wal.first().snapshot["label"]?.jsonPrimitive?.content)

        repeat(99) { index ->
            val snapshot = workspaceSnapshot(index + 2)
            persistence.recordMutation(snapshot, walEntry(index + 2, snapshot))
        }
        val envelopeAfterHundred = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelopeAfterHundred)
        assertEquals(100, envelopeAfterHundred.wal.size)
        assertEquals(listOf("n-1", "n-2", "n-3"), envelopeAfterHundred.wal.take(3).map { it.snapshot["label"]!!.jsonPrimitive.content })
        assertEquals("n-100", envelopeAfterHundred.wal.last().snapshot["label"]!!.jsonPrimitive.content)
    }

    @Test fun indexedDbUnavailableDegrades() = runTest {
        val store = InMemoryForgePersistenceStore(failIndexedDb = true)
        val persistence = ForgePersistenceCoordinator(store)
        var snapshot = workspaceSnapshot(0)

        repeat(101) { index ->
            snapshot = workspaceSnapshot(index + 1)
            persistence.recordMutation(snapshot, walEntry(index + 1, snapshot))
        }

        val persisted = persistence.persistSnapshot(snapshot)
        assertEquals("LocalStorageOnly", persistence.mode)
        assertTrue(persistence.warning.contains("localStorage") || persistence.warning.contains("fallback"))
        assertTrue(persisted.snapshot["label"]!!.jsonPrimitive.content == "n-101")

        val loaded = persistence.loadLatest(workspaceSnapshot(-1))
        assertEquals("n-101", loaded["label"]!!.jsonPrimitive.content)
        val envelope = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelope)
        assertEquals(1, envelope.wal.size)
    }

    @Test fun loadsOldSnapshot() = runTest {
        val store = InMemoryForgePersistenceStore()
        val old = buildJsonObject {
            put("snapshot", workspaceSnapshot(7))
            put("source", "indexeddb")
            put("version", 1)
        }
        store.writeLocalStorage(old.toString())
        val persistence = ForgePersistenceCoordinator(store)

        val loaded = persistence.loadLatest(workspaceSnapshot(0))
        assertEquals("n-7", loaded["label"]!!.jsonPrimitive.content)
        assertEquals(7, loaded["counter"]!!.jsonPrimitive.intOrNull)
        assertEquals("", decodePersistenceEnvelope(store.readLocalStorage())?.warning)
    }

    @Test fun walRotates() = runTest {
        val store = InMemoryForgePersistenceStore()
        val persistence = ForgePersistenceCoordinator(store)
        var snapshot = workspaceSnapshot(0)

        repeat(250) { index ->
            snapshot = workspaceSnapshot(index + 1)
            persistence.recordMutation(snapshot, walEntry(index + 1, snapshot))
        }

        val envelope = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelope)
        assertTrue(envelope.wal.size <= 100)
        assertEquals("n-250", envelope.wal.last().snapshot["label"]!!.jsonPrimitive.content)

        val loaded = persistence.loadLatest(workspaceSnapshot(-1))
        assertEquals("n-250", loaded["label"]!!.jsonPrimitive.content)
        assertEquals(250, loaded["counter"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun persistLatencyBudget() = runTest {
        val store = InMemoryForgePersistenceStore()
        val persistence = ForgePersistenceCoordinator(store)
        val workspace = syntheticWorkspace(cardCount = 500, blocksPerCard = 5)
        val samplesMs = buildList {
            repeat(100) { iteration ->
                val start = TimeSource.Monotonic.markNow()
                persistence.persistSnapshot(workspace)
                val elapsedMs = start.elapsedNow().inWholeMicroseconds / 1000.0
                if (iteration >= 10) add(elapsedMs)
            }
        }

        assertEquals(90, samplesMs.size)
        val p95 = percentile(samplesMs, 95)
        assertTrue(p95 < 50.0, "expected 500-card workspace persist p95 < 50ms, saw ${p95}ms")
        assertTrue(samplesMs.maxOrNull()!! < 4.0, "expected no persist iteration above 4ms, saw ${samplesMs.maxOrNull()}ms")
    }

    @Test fun priorValSuitesRemainGreen() {
        ForgeDocTest().emptyDocumentHasPageAndFirstChild()
        ForgeDocTest().forgeDocumentProjectsToKanbanBoard()
        ForgeBoardFSMTest().`loadDefault populates board and selects it`()
        val binding = CCEK.initialize(MuxReactorElement())
        binding.choreograph(ForgeDoc.empty("Regression guard")).cancel()
        ReteAgentTest().sinkFeedFiresRulesAsynchronously()
        LcncReductionCoreTest().testForgeKeyHierarchy()
        BlackboardSurfaceProjectionTest().lcncEntitiesProjectFirstAndCausalNodesAttachByCausalKey()
    }

    private fun workspaceSnapshot(counter: Int) = buildJsonObject {
        put("counter", counter)
        put("label", "n-$counter")
        put("title", "Workspace $counter")
    }

    private fun syntheticWorkspace(cardCount: Int, blocksPerCard: Int): JsonObject = buildJsonObject {
        put("title", "Latency Workspace")
        put("cards", buildJsonArray {
            repeat(cardCount) { cardIndex ->
                add(buildJsonObject {
                    val cardNumber = cardIndex + 1
                    put("id", "card-$cardNumber")
                    put("title", "Card $cardNumber")
                    put("status", if (cardIndex % 2 == 0) "backlog" else "in-progress")
                    put("updatedAtMs", cardNumber * 10L)
                    put("blocks", buildJsonArray {
                        repeat(blocksPerCard) { blockIndex ->
                            val blockNumber = blockIndex + 1
                            add(buildJsonObject {
                                put("id", "card-$cardNumber-block-$blockNumber")
                                put("kind", if (blockIndex % 2 == 0) "text" else "check")
                                put("text", "Block $blockNumber for card $cardNumber")
                                put("order", blockIndex)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun percentile(values: List<Double>, percentile: Int): Double {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile) / 100
        return sorted[index]
    }

    private fun walEntry(counter: Int, snapshot: kotlinx.serialization.json.JsonObject) = ForgeWalEntry(
        id = "mut-$counter",
        kind = "workspace-mutation",
        label = "n-$counter",
        source = "forge",
        timestampMs = counter.toLong(),
        snapshot = snapshot,
    )
}
