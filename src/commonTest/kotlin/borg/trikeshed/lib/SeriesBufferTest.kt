package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeriesBufferTest {

    @Test
    fun testAppendOrderAndCapacity() {
        val buffer = SeriesBuffer<Int>(capacity = 2)
        assertEquals(0, buffer.size)

        buffer.add(1)
        buffer.add(2)
        buffer.add(3) // triggers resize

        assertEquals(3, buffer.size)
        assertEquals(1, buffer[0])
        assertEquals(2, buffer[1])
        assertEquals(3, buffer[2])
    }

    @Test
    fun testRemoveLast() {
        val buffer = SeriesBuffer<String>()
        buffer.add("a")
        buffer.add("b")

        val last = buffer.removeLast()
        assertEquals("b", last)
        assertEquals(1, buffer.size)
        assertEquals("a", buffer[0])

        buffer.removeLast()
        assertEquals(0, buffer.size)

        assertFailsWith<IllegalArgumentException> {
            buffer.removeLast()
        }
    }

    @Test
    fun testSnapshotIsolation() {
        val buffer = SeriesBuffer<Int>()
        buffer.add(10)
        buffer.add(20)

        val snapshot = buffer.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(10, snapshot[0])
        assertEquals(20, snapshot[1])

        buffer.add(30)
        buffer.removeLast() // remove 30
        buffer.removeLast() // remove 20
        buffer.add(40) // now buffer has 10, 40

        // snapshot should remain unchanged
        assertEquals(2, snapshot.size)
        assertEquals(10, snapshot[0])
        assertEquals(20, snapshot[1])
    }
}
