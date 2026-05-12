package borg.trikeshed.collections.associative

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ContextEvictingMapTest {

    @Test
    fun nodeOwnerCompletionEvictsThatNode() = runTest {
        val map = ContextEvictingMap<CharSequence, Int>(SupervisorJob())
        val owner = Job()

        map.bind("a", owner, 7)
        assertEquals(7, map["a"])

        owner.cancel()
        advanceUntilIdle()

        assertNull(map["a"])
        assertEquals(0, map.size())
    }

    @Test
    fun mapOwnerCompletionClearsWholeMap() = runTest {
        val mapOwner = SupervisorJob()
        val map = ContextEvictingMap<CharSequence, Int>(mapOwner)

        map.bind("a", Job(), 1)
        map.bind("b", Job(), 2)
        assertEquals(2, map.size())

        mapOwner.cancel()
        advanceUntilIdle()

        assertNull(map["a"])
        assertNull(map["b"])
        assertEquals(0, map.size())
    }

    @Test
    fun explicitEvictHotKillsSingleNode() = runTest {
        val map = ContextEvictingMap<CharSequence, Int>(SupervisorJob())

        map.bind("a", Job(), 9)
        map.bind("b", Job(), 11)

        assertEquals(9, map.evict("a"))
        assertNull(map["a"])
        assertEquals(11, map["b"])
    }

    @Test
    fun entriesOnlyExposeLiveNodes() = runTest {
        val map = ContextEvictingMap<CharSequence, Int>(SupervisorJob())
        val live = Job()
        val dead = Job()

        map.bind("live", live, 3)
        map.bind("dead", dead, 4)
        dead.cancel()
        advanceUntilIdle()

        val entries = map.entries()
        assertEquals(1, entries.a)
        assertEquals("live", entries.b(0).a)
        assertEquals(3, entries.b(0).b)
        assertFalse(map.containsKey("dead"))
    }
}
