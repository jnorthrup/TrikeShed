package borg.trikeshed.lib
import kotlin.test.Test
import kotlin.test.assertEquals

class CowSeriesHandleTest {
    @Test
    fun testAdd() {
        val cowSeries = borg.trikeshed.common.collections.s_[1, 2, 3].cow
        cowSeries.add(4)
        assertEquals(borg.trikeshed.common.collections.s_[1, 2, 3, 4].cpb.toList(), cowSeries.cpb.toList())
    }

    @Test
    fun testRemoveAt() {
        val cowSeries = borg.trikeshed.common.collections.s_[1, 2, 3].cow
        cowSeries.removeAt(1)
        assertEquals(borg.trikeshed.common.collections.s_[1, 3].cpb, cowSeries.cpb)
    }
}