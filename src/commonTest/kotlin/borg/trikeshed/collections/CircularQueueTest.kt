package borg.trikeshed.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircularQueueTest {

    @Test
    fun testBasicInsertion() {
        val q = CircularQueue<Int>(3)
        assertEquals(0, q.size)
        assertFalse(q.full)

        assertTrue(q.offer(1))
        assertEquals(1, q.size)

        q.offer(2)
        assertEquals(2, q.size)
        assertFalse(q.full)

        q.offer(3)
        assertEquals(3, q.size)
        assertTrue(q.full)

        assertEquals(listOf(1, 2, 3), q.toList())
    }

    @Test
    fun testEviction() {
        var evictedCount = 0
        var lastEvicted: Int? = null
        val q = CircularQueue<Int>(3, evict = {
            evictedCount++
            lastEvicted = it
        })

        q.offer(1)
        q.offer(2)
        q.offer(3)
        assertEquals(0, evictedCount)

        q.offer(4) // 1 should be evicted
        assertEquals(1, evictedCount)
        assertEquals(1, lastEvicted)
        assertEquals(3, q.size)
        assertTrue(q.full)
        assertEquals(listOf(2, 3, 4), q.toList())

        q.offer(5) // 2 should be evicted
        assertEquals(2, evictedCount)
        assertEquals(2, lastEvicted)
        assertEquals(listOf(3, 4, 5), q.toList())
    }

    @Test
    fun testAddAndPlusAssign() {
        val q = CircularQueue<String>(2)
        q.add("A")

        with(q) {
            this += "B"
            this + "C"
        }

        assertEquals(2, q.size)
        assertEquals(listOf("B", "C"), q.toList())
    }

    @Test
    fun testIterator() {
        val q = CircularQueue<Int>(3)
        q.offer(1)
        q.offer(2)
        q.offer(3)
        q.offer(4)

        val iter = q.iterator()
        assertTrue(iter.hasNext())
        assertEquals(2, iter.next())
        assertEquals(3, iter.next())
        assertEquals(4, iter.next())
        assertFalse(iter.hasNext())

        assertFailsWith<IllegalStateException> {
            iter.remove()
        }
    }

    @Test
    fun testUnsupportedOperations() {
        val q = CircularQueue<Int>(1)
        assertFailsWith<IllegalStateException> {
            q.poll()
        }
        assertFailsWith<IllegalStateException> {
            q.peek()
        }
    }
}
