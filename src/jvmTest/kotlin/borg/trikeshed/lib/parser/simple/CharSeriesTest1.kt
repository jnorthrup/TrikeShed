
package borg.trikeshed.lib.parser.simple

import borg.trikeshed.lib.size
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











}