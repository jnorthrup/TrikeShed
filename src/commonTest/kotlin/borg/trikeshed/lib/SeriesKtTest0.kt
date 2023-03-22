package borg.trikeshed.lib

import borg.trikeshed.common.collections.s_
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesKtTest0 {
    @Test
    fun combine() {
        // create several randomly ordered Series<String> with non-uniform values and combine them
        // into a single Series<String> and verify that the result is
        // ordered and contains all the elements of the original Series

        val s1 = s_["a", "b", "c", "d", "e", "f", "g", "h", "i"]
        val s2 = s_["j", "k", "l", "m", "n", "o", "p", "q", "r"]
        val s3 = s_["s", "t", "u", "v", "w", "x", "y", "z"]
        val s = combine(s1, s2, s3)//, s4, s5, s6, s7, s8, s9, s10, s11, s12, s13, s14, s15, s16, s17)

        // check several positions in the series
        assertEquals("a", s[0])
        assertEquals("b", s[1])
        assertEquals("c", s[2])
        assertEquals("d", s[3])
        assertEquals("e", s[4])
        assertEquals("f", s[5])
        assertEquals("g", s[6])
        assertEquals("h", s[7])
        assertEquals("i", s[8])

        assertEquals("j", s[9])
        assertEquals("k", s[10])
        assertEquals("l", s[11])

        assertEquals("m", s[12])
        assertEquals("n", s[13])
        assertEquals("o", s[14])
        assertEquals("p", s[15])
        assertEquals("q", s[16])
        assertEquals("r", s[17])

        assertEquals("s", s[18])
        assertEquals("t", s[19])
        assertEquals("u", s[20])
        assertEquals("v", s[21])
        assertEquals("w", s[22])
        assertEquals("x", s[23])
        assertEquals("y", s[24])
        assertEquals("z", s[25])

    }

    fun combine2() {
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
        assertEquals("f", s[5])
        assertEquals("g", s[6])
        assertEquals("h", s[7])
        assertEquals("i", s[8])

        assertEquals("j", s[9])
        assertEquals("k", s[10])
        assertEquals("l", s[11])

        assertEquals("m", s[12])
        assertEquals("n", s[13])
        assertEquals("o", s[14])
        assertEquals("p", s[15])
        assertEquals("q", s[16])
        assertEquals("r", s[17])

        assertEquals("s", s[18])
        assertEquals("t", s[19])
        assertEquals("u", s[20])
        assertEquals("v", s[21])
        assertEquals("w", s[22])
        assertEquals("x", s[23])
        assertEquals("y", s[24])
        assertEquals("z", s[25])

        assertEquals("a", s[26])
        assertEquals("b", s[27])
        assertEquals("c", s[28])
        assertEquals("d", s[29])
        assertEquals("e", s[30])
        assertEquals("f", s[31])
        assertEquals("g", s[32])
        assertEquals("h", s[33])
        assertEquals("i", s[34])

        assertEquals("j", s[35])
        assertEquals("k", s[36])
        assertEquals("l", s[37])

        assertEquals("m", s[38])
        assertEquals("n", s[39])
        assertEquals("o", s[40])
        assertEquals("p", s[41])
        assertEquals("q", s[42])
        assertEquals("r", s[43])

        assertEquals("s", s[44])
        assertEquals("t", s[45])
        assertEquals("u", s[46])
        assertEquals("v", s[47])
        assertEquals("w", s[48])
        assertEquals("x", s[49])
        assertEquals("y", s[50])
        assertEquals("z", s[51])

        assertEquals("a", s[52])
        assertEquals("b", s[53])
        assertEquals("c", s[54])
        assertEquals("d", s[55])
        assertEquals("e", s[56])
        assertEquals("f", s[57])
        assertEquals("g", s[58])
        assertEquals("h", s[59])
        assertEquals("i", s[60])

        assertEquals("j", s[61])
        assertEquals("k", s[62])
        assertEquals("l", s[63])

        assertEquals("m", s[64])
        assertEquals("n", s[65])
        assertEquals("o", s[66])
        assertEquals("p", s[67])
        assertEquals("q", s[68])

        // check the last position
        assertEquals("r", s[s.size - 1])
    }


    @Test
    fun testParseDouble() {
        val s = "1.234E-5".toSeries()
        val d = s.parseDouble()
        assertEquals(1.234E-5, d, 0.0000000000001)

    }

    @Test
    fun testParseDouble2() {
        val s = "-91.125".toSeries()
        val d = s.parseDouble()
        assertEquals(-91.125, d, 0.0000000000001)

    }
}