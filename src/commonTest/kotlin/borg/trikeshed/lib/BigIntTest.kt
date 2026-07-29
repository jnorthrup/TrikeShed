package borg.trikeshed.lib

import borg.trikeshed.num.BigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigIntTest {

    @Test
    fun testProcessMagnitudes_zero() {
        val a = BigInt(null, emptySeries())
        val b = BigInt(null, emptySeries())

        val resultAdd = a.processMagnitudes(b, true)
        assertTrue(resultAdd.isEmpty())

        val resultSub = a.processMagnitudes(b, false)
        assertTrue(resultSub.isEmpty())
    }

    @Test
    fun testProcessMagnitudes_singleLimb() {
        val a = BigInt(true, listOf(10u).toSeries())
        val b = BigInt(true, listOf(20u).toSeries())

        val resultAdd = a.processMagnitudes(b, true)
        assertEquals(1, resultAdd.size)
        assertEquals(30u, resultAdd[0])

        val a2 = BigInt(true, listOf(30u).toSeries())
        val b2 = BigInt(true, listOf(10u).toSeries())
        val resultSub = a2.processMagnitudes(b2, false)
        assertEquals(1, resultSub.size)
        assertEquals(20u, resultSub[0])
    }

    @Test
    fun testProcessMagnitudes_multiLimb() {
        val a = BigInt(true, listOf(5u, 1u).toSeries()) // Value: (1L shl 32) + 5
        val b = BigInt(true, listOf(10u, 2u).toSeries()) // Value: (2L shl 32) + 10

        val resultAdd = a.processMagnitudes(b, true)
        assertEquals(2, resultAdd.size)
        assertEquals(15u, resultAdd[0])
        assertEquals(3u, resultAdd[1])
    }

    @Test
    fun testProcessMagnitudes_carryOut() {
        val maxUInt = 0xFFFFFFFFu
        val a = BigInt(true, listOf(maxUInt).toSeries())
        val b = BigInt(true, listOf(5u).toSeries())

        val resultAdd = a.processMagnitudes(b, true)
        assertEquals(2, resultAdd.size)
        assertEquals(4u, resultAdd[0]) // 0xFFFFFFFF + 5 = 0x100000004 -> 4
        assertEquals(1u, resultAdd[1]) // carry 1
    }

    @Test
    fun testProcessMagnitudes_signPreservationThroughOperations() {
        val a = BigInt(true, listOf(10u).toSeries())
        val b = BigInt(true, listOf(20u).toSeries())

        // Let's test the public API using processMagnitudes for sign processing
        val resultAdd = a + b
        assertEquals(true, resultAdd.sign)
        assertEquals(1, resultAdd.magnitude.size)
        assertEquals(30u, resultAdd.magnitude[0])

        val a2 = BigInt(false, listOf(10u).toSeries()) // -10
        val b2 = BigInt(false, listOf(20u).toSeries()) // -20
        val resultSubAdd = a2 + b2
        assertEquals(false, resultSubAdd.sign)
        assertEquals(1, resultSubAdd.magnitude.size)
        assertEquals(30u, resultSubAdd.magnitude[0])
    }
}
