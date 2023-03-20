package borg.trikeshed.lib

import kotlin.test.Test


class SeriesKtTest1 {

    @Test
    fun testStringToSeries() {
        val s = "hello"
        val series = s.toSeries()
        val s2 = series.asString()
        assert(s == s2)
    }

    @Test
    fun main() {
        //verify that seekTo('\n') returns exclusive pos from the eol
        val bs = ByteSeries("a\nbc\ncde\n")
        bs.pos(0)
        bs.seekTo('\n'.code.toByte())
        println(bs.pos)
        assert(bs.pos == 2)

    }
}