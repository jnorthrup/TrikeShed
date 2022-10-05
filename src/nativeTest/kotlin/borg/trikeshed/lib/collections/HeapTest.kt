package borg.trikeshed.lib.collections

import kotlin.test.*

class HeapTest {

    @Test
    fun add() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.remove())
        assertEquals(2, h.remove())
        assertEquals(3, h.remove())
    }

    @Test
    fun remove() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.remove())
        assertEquals(2, h.remove())
        assertEquals(3, h.remove())
    }

    @Test
    fun poll() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.poll())
        assertEquals(2, h.poll())
        assertEquals(3, h.poll())
    }

    @Test
    fun element() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.element())
        assertEquals(1, h.element())
        assertEquals(1, h.element())
    }

    @Test
    fun peek() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(1, h.peek())
        assertEquals(1, h.peek())
        assertEquals(1, h.peek())
    }

    @Test
    fun size() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(3, h.size())
        h.remove()
        assertEquals(2, h.size())
        h.remove()
        assertEquals(1, h.size())
        h.remove()
        assertEquals(0, h.size())
    }

    @Test
    fun isEmpty() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(false, h.isEmpty())
        h.remove()
        assertEquals(false, h.isEmpty())
        h.remove()
        assertEquals(false, h.isEmpty())
        h.remove()
        assertEquals(true, h.isEmpty())
    }

    @Test
    fun clear() {
        val h = Heap<Int>()
        h.add(1)
        h.add(2)
        h.add(3)

        assertEquals(false, h.isEmpty())
        h.clear()
        assertEquals(true, h.isEmpty())
    }
}
