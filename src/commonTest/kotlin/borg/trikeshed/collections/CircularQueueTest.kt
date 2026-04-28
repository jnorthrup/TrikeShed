package borg.trikeshed.collections

import kotlin.test.Test
import kotlin.test.assertEquals

class CircularQueueTest {
    @Test
    fun `offer and poll is FIFO`() {
        val q = CirQlar<Int>(3)
        q.offer(1)
        q.offer(2)
        q.offer(3)
        assertEquals(1, q.poll())
        assertEquals(2, q.poll())
        assertEquals(3, q.poll())
    }

    @Test
    fun `peek does not remove`() {
        val q = CirQlar<Int>(3)
        q.offer(10)
        assertEquals(10, q.peek())
        assertEquals(10, q.poll())
    }
}
