
package borg.trikeshed.lib.parser.simple

import borg.trikeshed.lib.*
import kotlin.test.*


class CharSeriesTest {

    val csShort=(CharSeries("abc"))
    val csLong=CharSeries("The quick brown fox jumps over the lazy dog")
    @Test
    fun testSlice() {
        var csl = csLong.clone()
        csl.limit = 10
        var slice = csl.slice
        assertEquals("The quick ", slice.asString())
        assertEquals("The quick brown fox jumps over the lazy dog", csLong.slice.asString())
        assertEquals("abc", csShort.slice.asString())
        csl = csLong.clone()
        slice = csl.pos(10).slice
        assertEquals("brown fox jumps over the lazy dog", slice.asString())
        csl = csLong.clone()
        slice = csl.lim(14).pos(10).slice
        assertEquals("brow", slice.asString())
        csl = csLong.clone()
        (csl.lim(14).pos(10)).fl //flip
        assertEquals("The quick ", csl.asString())
    }

    @Test
    fun drop() {

        val s = CharSeries((0..9).toSeries() α { it: Int ->it.digitToChar() })
      val s1= CharSeries(s.drop(5))
        assertEquals(5, s1.size)
        assertEquals('5', s1[0] )
        assertEquals('9', s1[4] )
val s2=s1.clr
        assertEquals(5, s2.size)
    }


}