package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TwinTest {
    @Test
    fun testTwinPacked() {
        val tp = TwinPacked.of(1000, 500)
        assertEquals(1000, tp.start)
        assertEquals(500, tp.len)
        assertEquals("TwinPacked(start=1000,len=500)", tp.toString())
    }

    @Test
    fun testTwin8() {
        val t8 = Twin8.of(10, 200)
        assertEquals(10, t8.offset)
        assertEquals(200, t8.len)
        val tp2 = t8.toPacked(1000)
        assertEquals(1010, tp2.start)
        assertEquals(200, tp2.len)
    }

    @Test
    fun testTwin8RejectsLenTooLarge() {
        assertFails { Twin8.of(1, 0x1FF) }
    }
}
