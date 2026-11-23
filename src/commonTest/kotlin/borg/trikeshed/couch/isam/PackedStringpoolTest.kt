package borg.trikeshed.couch.isam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackedStringpoolTest {
    @Test fun anIdIsTheOrdinalOfTheFirstPutAndStays() {
        val pool = PackedStringpool(2)
        assertEquals(0, pool.put("a"))
        assertEquals(1, pool.put("b"))
        assertEquals(0, pool.put("a"))
        assertEquals(2, pool.size)
        assertEquals("a", pool[0])
        assertEquals("b", pool[1])
        assertNull(pool[2])
        assertNull(pool[-1])
    }

    @Test fun theEmptyStringAndMultibyteStringsRoundTrip() {
        val pool = PackedStringpool()
        val text = "café — 日本語 \uD83D\uDE00"
        val empty = pool.put("")
        val multibyte = pool.put(text)
        assertEquals("", pool[empty])
        assertEquals(text, pool[multibyte])
        assertEquals(multibyte, pool.find(text))
        assertEquals(empty, pool.find(""))
        assertEquals(-1, pool.find("cafe"))
        assertEquals(-1, pool.find("日本"))
        assertEquals(2, pool.put("日本"))
        assertEquals(2, pool.find("日本"))
        assertEquals("日本", pool[2])
    }

    @Test fun growthKeepsEveryIdAndFindsEveryString() {
        val pool = PackedStringpool(1)
        val n = 20_000
        fun lexeme(i: Int) = "https://trikeshed.borg/forge#constellation/hohenwald-tn/norm/$i"
        for (i in 0 until n) assertEquals(i, pool.put(lexeme(i)))
        assertEquals(n, pool.size)
        for (i in 0 until n step 97) {
            assertEquals(i, pool.find(lexeme(i)))
            assertEquals(lexeme(i), pool[i])
            assertEquals(i, pool.put(lexeme(i)))
        }
        assertEquals(-1, pool.find(lexeme(n)))
        assertEquals(n, pool.size)
    }
}
