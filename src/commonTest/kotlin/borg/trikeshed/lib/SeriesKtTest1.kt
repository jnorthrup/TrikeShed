package borg.trikeshed.lib

import kotlin.test.*


class SeriesKtTest1{

    @Test
    fun testStringToSeries(){
        val s = "hello"
        val series = s.toSeries()
        val s2 = series.asString()
        assert(s == s2)
    }

}