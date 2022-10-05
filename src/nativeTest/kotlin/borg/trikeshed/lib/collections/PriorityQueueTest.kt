package borg.trikeshed.lib.collections

import kotlin.test.Test
import kotlin.test.assertEquals

class PriorityQueueTest {

    @Test
    fun remove() {
        val h = PriorityQueue<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.remove())
        assertEquals(2, h.remove())
        assertEquals(3, h.remove())
    }

    @Test
    fun poll() {
        val h = PriorityQueue<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.poll())
        assertEquals(2, h.poll())
        assertEquals(3, h.poll())
    }

    @Test
    fun element() {
        val h = PriorityQueue<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.element())
        assertEquals(1, h.element())
        assertEquals(1, h.element())
    }

    @Test
    fun peek() {
        val h = PriorityQueue<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.peek())
        assertEquals(1, h.peek())
        assertEquals(1, h.peek())
    }

    @Test
    fun add() {
        val h = PriorityQueue<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.remove())
        assertEquals(2, h.remove())
        assertEquals(3, h.remove())
    }
}
