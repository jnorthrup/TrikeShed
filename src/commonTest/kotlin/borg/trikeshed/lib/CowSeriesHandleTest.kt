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


//
//    @Test
//    fun testRemove() {
//        val cowSeries = s_[1,2,3].cow
//        assertTrue(cowSeries.remove(2))
//        assertFalse(cowSeries.remove(2))
//        assertEquals(s_(1, 3), cowSeries.toList())
//    }
//
//    @Test
//    fun testClear() {
//        val cowSeries = s_[1,2,3].cow
//        cowSeries.clear()
//        assertTrue(cowSeries.isEmpty())
//    }
//
//    @Test
//    fun testAppend() {
//        val cowSeries = s_[1,2,3].cow
//        cowSeries.append(4)
//        assertEquals(s_(1, 2, 3, 4), cowSeries.toList())
//    }
//
//    @Test
//    fun testPlus() {
//        val cowSeries = s_[1,2,3].cow
//        cowSeries.plus(4)
//        assertEquals(s_(1, 2, 3, 4), cowSeries.toList())
//    }
//
//    @Test
//    fun testMinus() {
//        val cowSeries = s_[1,2,3].cow
//        cowSeries.minus(2)
//        assertEquals(s_(1, 3), cowSeries.toList())
//    }
//
//    @Test
//    fun testPlusAssign() {
//        val cowSeries = s_[1,2,3].cow
//        cowSeries.plusAssign(4)
//        assertEquals(s_(1, 2, 3, 4), cowSeries.toList())
//    }
//
//    @Test
//    fun testMinusAssign() {
//        val cowSeries = s_[1,2,3].cow
//        cowSeries.minusAssign(2)
//        assertEquals(s_(1, 3), cowSeries.toList())
//    }
}