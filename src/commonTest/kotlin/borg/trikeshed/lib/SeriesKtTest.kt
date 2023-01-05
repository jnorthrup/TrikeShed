package borg.trikeshed.lib

import borg.trikeshed.common.collections.s_
import kotlin.test.*

//class SeriesKtTest {
//    @Test
//    fun drop() {
//        //drop unit test:
//    val s = (0..10).toSeries()
//    //s.size==11
//        //11-5=6
//    val s2 = s.drop(5)
//    assertEquals(6, s2.size)
//    assertEquals(5, s2[0])
//    assertEquals(9, s2[4])
//
//    }
//
//    @Test
//    fun take() {
//    }
//}

class SeriesKtTest {
    @Test
    fun combine() {
        // create several randomly ordered Series<String> with non-uniform values and combine them
        // into a single Series<String> and verify that the result is
        // ordered and contains all the elements of the original Series

        val s1 = s_["a", "b", "c", "d", "e", "f", "g", "h", "i"]
        val s2 = s_["j", "k", "l", "m", "n", "o", "p", "q", "r"]
        val s3 = s_["s", "t", "u", "v", "w", "x", "y", "z"]
        val s4 = s_["a", "b", "c", "d", "e", "f", "g", "h", "i"]
        val s5 = s_["j", "k", "l", "m", "n", "o", "p", "q", "r"]
        val s6 = s_["s", "t", "u", "v", "w", "x", "y", "z"]
        val s7 = s_["a", "b", "c", "d", "e", "f", "g", "h", "i"]
        val s8 = s_["j", "k", "l", "m", "n", "o", "p", "q", "r"]
        val s9 = s_["s", "t", "u", "v", "w", "x", "y", "z"]
        val s10 = s_["a", "b", "c", "d", "e", "f", "g", "h", "i"]
        val s11 = s_["j", "k", "l", "m", "n", "o", "p", "q", "r"]
        val s12 = s_["s", "t", "u", "v", "w", "x", "y", "z"]
        val s13 = s_["a", "b", "c", "d", "e", "f", "g", "h", "i"]
        val s14 = s_["j", "k", "l", "m", "n", "o", "p", "q", "r"]
        val s15 = s_["s", "t", "u", "v", "w", "x", "y", "z"]
        val s16 = s_["a", "b", "c", "d"]
        val s17 = s_["j", "k", "l", "m", "n", "o", "p", "q", "r"]

        val s = combine(s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12, s13, s14, s15, s16, s17)

        // check several positions in the series
        assertEquals("a", s[0])
        assertEquals("b", s[1])
        assertEquals("c", s[2])
        assertEquals("d", s[3])
        assertEquals("e", s[4])

        // check the last position
        assertEquals("r", s[s.size - 1])
    }


    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun testParseDouble() {
        val s = "1.234E-5".toSeries()
        val d = s.parseDouble()
        assertEquals(1.234E-5, d, 0.0000000000001)

    }
}