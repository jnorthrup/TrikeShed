package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reactor owns keymux/modelmux; Kanban FSM consumes reactor state.
 *
 * This test verifies the boundary is real:
 *  1. The reactor's load path emits KanbanEvent.CredentialLoaded.
 *  2. The reactor's tick path emits KanbanEvent.KeyLeased.
 *  3. KanbanFSM reduces those events into a single KanbanState the UI can render.
 */
class KanbanReactorFSMTest {

    @Test
    fun reactorEmitsKanbanEventsAndFsmReducesThem() = runTest {
        KanbanFSM.reset()
        val reactor = openMuxReactorElement(
            config = MuxReactorConfig(maxInProgress = 1, maxSpawn = 1, maxPerProvider = 1),
        )

        // 1. Load credentials from a typed pool shape (no Python, no Hermes CLI).
        reactor.loadCredentialPool(
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
                "anthropic" to listOf(
                    MuxCredentialRecord(
                        id = "key-anthropic-1",
                        label = "Anthropic primary",
                        baseUrl = "https://api.anthropic.com",
                    ),
                ),
            ),
        )

        // 2. One tick leases the active nvidia key.
        val dispatch = reactor.tick()
        assertEquals(1, dispatch.spawned)

        // 3. The reactor's event stream carried all three transitions in order:
        //    CredentialLoaded(nvidia), CredentialLoaded(anthropic), KeyLeased(nvidia key).
        val events = reactor.kanbanEvents.take(3).toList()
        assertEquals(3, events.size)
        assertTrue(events[0] is KanbanEvent.CredentialLoaded)
        assertEquals("nvidia", (events[0] as KanbanEvent.CredentialLoaded).provider)
        assertEquals("key-nvidia-1", (events[0] as KanbanEvent.CredentialLoaded).keyId)
        assertTrue(events[1] is KanbanEvent.CredentialLoaded)
        assertEquals("anthropic", (events[1] as KanbanEvent.CredentialLoaded).provider)
        assertTrue(events[2] is KanbanEvent.KeyLeased)
        assertEquals("key-nvidia-1", (events[2] as KanbanEvent.KeyLeased).keyId)

        // 4. KanbanFSM reduces the same events into board state.
        val reduced = events.fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }
        assertEquals(listOf("nvidia", "anthropic"), reduced.activeProviders)
        assertEquals(listOf("key-nvidia-1"), reduced.leasedKeyIds)
        assertEquals("KeyLeased", reduced.lastEventKind)
    }

    @Test
    fun fsmReduceReclaimDecrementsLeasedKeys() = runTest {
        KanbanFSM.reset()
        val leased = KanbanEvent.KeyLeased(
            keyId = "key-a",
            leasedTo = "reactor-agent-1",
            leaseExpiresAt = 1L,
            timestampMs = 100L,
        )
        val reclaimed = KanbanEvent.LeaseReclaimed(
            keyId = "key-a",
            previousLeasee = "reactor-agent-1",
            timestampMs = 200L,
        )
        val after = KanbanFSM.reduce(reclaimed, KanbanFSM.reduce(leased))
        assertEquals(emptyList(), after.leasedKeyIds)
        assertEquals(1, after.reclaimedCount)
        assertEquals("LeaseReclaimed", after.lastEventKind)
        assertEquals(200L, after.lastEventTimestampMs)
    }

    @Test
    fun reducerIsDeterministic() = runTest {
        KanbanFSM.reset()
        val event = KanbanEvent.CredentialLoaded(
            provider = "openai",
            keyId = "key-openai-1",
            timestampMs = 42L,
        )
        val a = KanbanFSM.reduce(event)
        val b = KanbanFSM.reduce(event)
        assertEquals(a, b)
        assertEquals(listOf("openai"), a.activeProviders)
        // sanity: flow exposes the same value
        assertEquals(a, KanbanFSM.flow.value)
    }

    /**
     * Endgame cut #1: the kanban can consume a taxonomy-node creation event
     * (from a Notion-adjacent AI taxonomical creator) and reflect it in board
     * state. This is the narrowest bridge between the Notion clone's creation
     * surface and the kanban's render surface — the first KanbanEvent that is
     * not about keymux/modelmux.
     */
    @Test
    fun taxonomyNodeCreatedEventRollsIntoBoardState() = runTest {
        KanbanFSM.reset()
        val e1 = KanbanEvent.TaxonomyNodeCreated(
            nodeId = "blk-1",
            kind = "database",
            label = "Endpoints",
            parentId = null,
            timestampMs = 100L,
        )
        val e2 = KanbanEvent.TaxonomyNodeCreated(
            nodeId = "blk-2",
            kind = "row",
            label = "GET /health",
            parentId = "blk-1",
            timestampMs = 200L,
        )
        val s1 = KanbanFSM.reduce(e1)
        assertEquals(1, s1.taxonomyNodeCount)
        assertEquals(listOf("Endpoints"), s1.recentTaxonomyNodes)
        assertEquals("TaxonomyNodeCreated", s1.lastEventKind)
        assertEquals(100L, s1.lastEventTimestampMs)

        val s2 = KanbanFSM.reduce(e2, s1)
        assertEquals(2, s2.taxonomyNodeCount)
        assertEquals(listOf("Endpoints", "GET /health"), s2.recentTaxonomyNodes)
        assertEquals(200L, s2.lastEventTimestampMs)

        // Folding a mixed stream still works — taxonomy events coexist with
        // the pre-existing credential/lease events without interference.
        KanbanFSM.reset()
        val mixed = listOf<KanbanEvent>(
            KanbanEvent.CredentialLoaded("openai", "key-1", 1L),
            KanbanEvent.TaxonomyNodeCreated("blk-3", "heading", "Phase 1", null, 2L),
        )
        val reduced = mixed.fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }
        assertEquals(listOf("openai"), reduced.activeProviders)
        assertEquals(1, reduced.taxonomyNodeCount)
        assertEquals(listOf("Phase 1"), reduced.recentTaxonomyNodes)
    }
}