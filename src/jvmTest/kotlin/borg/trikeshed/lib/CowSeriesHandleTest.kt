package borg.trikeshed.lib

import borg.trikeshed.common.collections.s_
import kotlin.test.*

class CowSeriesHandleTest {

    @Test
    fun set() {
        val series = s_[1, 2, 3, 4, 5].cow
        series.set(2, 99)
        assertEquals(99, series[2])
    }

    @Test
    fun add() {
        val series = s_[1, 2, 3, 4, 5].cow
        series.add(99)
        assertEquals(99, series[5])

    }

    @Test
    fun plus() {
        val series = s_[1, 2, 3, 4, 5].cow
        series + 99
        assertEquals(99, series[5])
    }

    @Test
    fun get() {
        val series = s_[1, 2, 3, 4, 5].cow
        assertEquals(3, series[2])

    }
    @Test
    fun rm() {

        val series = s_[1, 2, 3, 4, 5].cow
        series.removeAt(2)
        assertEquals(4, series[2])

    }
}