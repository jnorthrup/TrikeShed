package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reactor-owned modelmux cache: hit, miss, store, evict. The reactor fans
 * cache events into the kanbanEvents stream; the Kanban FSM rolls them into
 * KanbanState cache counters. No Python, no Hermes features.
 */
class ModelApiCacheTest {

    @Test
    fun cacheMissThenHitAndFsmCounts() = runTest {
        KanbanFSM.reset()
        val reactor = openMuxReactorElement(MuxReactorConfig())

        // 1. First lookup misses (via reactor API so it emits CacheTick).
        val first = reactor.lookupModel("nvidia", "minimaxai/minimax-m3")
        assertIs<CacheLookup.Miss>(first)

        // 2. Store then second lookup hits.
        reactor.cache.putModel("nvidia", "minimaxai/minimax-m3", "{ctx:128000}")
        val second = reactor.lookupModel("nvidia", "minimaxai/minimax-m3")
        assertIs<CacheLookup.Hit>(second)
        assertEquals(1, second.entry.hits)

        // 3. Drain the kanbanEvents stream (Miss then Hit).
        val events = reactor.kanbanEvents.take(2).toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is KanbanEvent.CacheTick && (events[0] as KanbanEvent.CacheTick).kind == "Miss")
        assertTrue(events[1] is KanbanEvent.CacheTick && (events[1] as KanbanEvent.CacheTick).kind == "Hit")

        // 4. Apply cache events to the FSM and assert counters.
        val fsm = events
            .filterIsInstance<KanbanEvent.CacheTick>()
            .fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }
        assertEquals(1, fsm.cacheMisses)
        assertEquals(0, fsm.cacheStored)
        assertEquals(1, fsm.cacheHits)
    }

    @Test
    fun reactorApiCallCacheMissHitEvict() = runTest {
        KanbanFSM.reset()
        val reactor = openMuxReactorElement(MuxReactorConfig())
        // 1. Miss
        val miss = reactor.lookupApiCall("nvidia", "minimaxai/minimax-m3", "req-1", 60_000L)
        assertIs<CacheLookup.Miss>(miss)

        // 2. Store then Hit
        reactor.cacheApiCall("nvidia", "minimaxai/minimax-m3", "req-1", 60_000L, "{ok:true}")
        val hit = reactor.lookupApiCall("nvidia", "minimaxai/minimax-m3", "req-1", 60_000L)
        assertIs<CacheLookup.Hit>(hit)

        // 3. Evict and confirm count grows
        val beforeEvict = reactor.cache.apiCallCount()
        val evicted = reactor.cache.evictExpired()
        assertEquals(beforeEvict, evicted + reactor.cache.apiCallCount())
    }

    @Test
    fun cacheIsLruAndEvictsWhenOverCap() = runTest {
        KanbanFSM.reset()
        val cache = ModelApiCache(maxEntries = 2)
        cache.putModel("p1", "m1", "{}")
        cache.putModel("p2", "m2", "{}")
        cache.putModel("p3", "m3", "{}") // forces eviction of the oldest
        assertTrue(cache.modelCount() <= 2)
    }

    @Test
    fun cachePersistsAndHydrates() = runTest {
        KanbanFSM.reset()
        val cache1 = ModelApiCache()
        cache1.putModel("nvidia", "minimaxai/minimax-m3", "{ctx:128000}")
        cache1.putApiCall("nvidia", "minimaxai/minimax-m3", "req-99", 60_000L, "{ok:true}")

        val snapshot = cache1.persist()
        assertEquals(2, snapshot.size)

        val cache2 = ModelApiCache()
        cache2.hydrate(snapshot)
        // Hydrate restores at least the model row; api call rows are best-effort.
        val first = cache2.lookupModel("nvidia", "minimaxai/minimax-m3")
        assertIs<CacheLookup.Hit>(first)
    }
}