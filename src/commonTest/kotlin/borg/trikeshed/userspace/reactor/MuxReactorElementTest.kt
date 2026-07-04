package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MuxReactorElementTest {

    @Test
    fun reactorOwnsTaxonomyEventIngress() = runTest {
        KanbanFSM.reset()
        val reactor = openMuxReactorElement()

        val accepted = reactor.ingestTaxonomyEvents(
            listOf(
                KanbanEvent.TaxonomyNodeCreated(
                    nodeId = "blk-1",
                    kind = "heading_1",
                    label = "REST APIs",
                    parentId = null,
                    timestampMs = 100L,
                ),
                KanbanEvent.TaxonomyNodeCreated(
                    nodeId = "blk-2",
                    kind = "heading_2",
                    label = "REST APIs — Definition",
                    parentId = "blk-1",
                    timestampMs = 200L,
                ),
            ),
        )

        advanceUntilIdle()

        assertEquals(2, accepted)
        val replayed = reactor.kanbanEvents.replayCache.filterIsInstance<KanbanEvent.TaxonomyNodeCreated>()
        assertEquals(listOf("REST APIs", "REST APIs — Definition"), replayed.map { it.label })

        // Manually reduce events like KanbanReactorFSMTest does
        val reduced = replayed.fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }
        assertEquals(2, reduced.taxonomyNodeCount)
        assertEquals(listOf("REST APIs", "REST APIs — Definition"), reduced.recentTaxonomyNodes)
    }

    @Test
    fun reactorOwnsMuxStateAndTicksLeases() = runTest {
        val reactor = openMuxReactorElement(
            config = MuxReactorConfig(maxInProgress = 1, maxSpawn = 1, maxPerProvider = 1),
        )

        val loaded = reactor.loadCredentialPool(
            mapOf(
                "nvidia" to listOf(
                    MuxCredentialRecord(
                        id = "key-nvidia-1",
                        label = "NVIDIA primary",
                        baseUrl = "https://integrate.api.nvidia.com/v1",
                        lastModel = "minimaxai/minimax-m3",
                        requestCount = 7,
                    ),
                ),
            ),
        )
        assertEquals(1, loaded)

        reactor.recordModel(
            keyId = "key-nvidia-1",
            modelId = "minimaxai/minimax-m3",
            provider = "nvidia",
            contextWindow = 128_000,
        )

        val beforeTick = reactor.flowState.value
        assertEquals(1, beforeTick.availableKeys)
        assertEquals("minimaxai/minimax-m3", beforeTick.draftMapping["key-nvidia-1"])

        val dispatch = reactor.tick()
        assertEquals(1, dispatch.spawned)

        val afterTick = reactor.flowState.value
        assertEquals(1, afterTick.currentlyRunning)
        assertEquals(0, afterTick.availableKeys)
        assertNotNull(afterTick.leases.singleOrNull { it.keyId == "key-nvidia-1" })
    }
}
