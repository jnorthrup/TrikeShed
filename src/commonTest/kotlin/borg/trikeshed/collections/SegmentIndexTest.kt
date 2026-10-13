package borg.trikeshed.collections

import kotlin.test.*

class SegmentIndexTest {
    @Test fun compactionPreservesNewestPayloadsAndSubsequentAppends() {
        val index = SegmentIndex(tailCapacity = 7)
        val expected = mutableMapOf<Long, Long>()
        for (i in 0 until 103) {
            val key = (i * 17 % 29 - 14).toLong()
            index.put(key, i.toLong())
            expected[key] = i.toLong()
        }
        for ((key, payload) in expected) assertEquals(payload, index.get(key))
        assertTrue(index.segments > 1)
        index.compact()
        assertEquals(1, index.segments)
        assertEquals(expected.size, index.size)
        for ((key, payload) in expected) assertEquals(payload, index.get(key))
        assertEquals(-99L, index.get(99, -99))
        index.put(0, 1000)
        index.put(Long.MIN_VALUE, 1001)
        index.put(Long.MAX_VALUE, 1002)
        index.compact()
        assertEquals(1000L, index.get(0))
        assertEquals(1001L, index.get(Long.MIN_VALUE))
        assertEquals(1002L, index.get(Long.MAX_VALUE))
        for ((key, payload) in expected) if (key != 0L) assertEquals(payload, index.get(key))
    }
}
