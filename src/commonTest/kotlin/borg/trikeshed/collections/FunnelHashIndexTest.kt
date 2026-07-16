package borg.trikeshed.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunnelHashIndexTest {

    @Test fun basicGet() {
        val keys = listOf("a", "b", "c", "d", "e")
        val idx = FunnelHashIndex.build(keys, 0x1234L)
        assertEquals(0, idx.get("a"))
        assertEquals(1, idx.get("b"))
        assertEquals(4, idx.get("e"))
        assertNull(idx.get("z"))
    }

    @Test fun contains() {
        val keys = listOf("a", "b", "c")
        val idx = FunnelHashIndex.build(keys, 0x1234L)
        assertTrue(idx.contains("a"))
        assertTrue(idx.contains("b"))
        assertTrue(idx.contains("c"))
        assertTrue(!idx.contains("d"))
    }

    @Test fun deterministic() {
        val keys = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val idx1 = FunnelHashIndex.build(keys, 0xdeadbeefL)
        val idx2 = FunnelHashIndex.build(keys, 0xdeadbeefL)
        keys.forEach { assertEquals(idx1.get(it), idx2.get(it)) }
        assertEquals(idx1.totalCapacity(), idx2.totalCapacity())
    }

    @Test fun emptyIndex() {
        val idx = FunnelHashIndex.build(emptyList<String>(), 0L)
        assertEquals(0, idx.totalCapacity())
        assertEquals(0, idx.size())
        assertNull(idx.get("anything"))
    }

    @Test fun singleKey() {
        val idx = FunnelHashIndex.build(listOf("solo"), 0x1234L)
        assertEquals(0, idx.get("solo"))
        assertNull(idx.get("other"))
    }

    @Test fun manyKeys() {
        val keys = (0 until 200).map { "key$it" }
        val idx = FunnelHashIndex.build(keys, 0xcafeL)
        keys.forEachIndexed { i, k -> assertEquals(i, idx.get(k)) }
        assertNull(idx.get("missing"))
    }

    @Test fun probeDistribution() {
        val keys = (0 until 50).map { "key$it" }
        val idx = FunnelHashIndex.build(keys, 0xcafeL)
        val probes = idx.probeDistribution()
        assertEquals(50, probes.size)
        assertTrue(probes.all { it > 0 })
    }
}