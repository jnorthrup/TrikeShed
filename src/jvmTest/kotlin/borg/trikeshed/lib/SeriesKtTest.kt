package borg.trikeshed.lib

import kotlin.test.*

class SeriesKtTest {
    @Test
    fun drop() {
        //drop unit test:
    val s = (0..10).toSeries()
    //s.size==11
        //11-5=6
    val s2 = s.drop(5)
    assertEquals(6, s2.size)
    assertEquals(5, s2[0])
    assertEquals(9, s2[4])

    }

    @Test
    fun take() {
    }
}

