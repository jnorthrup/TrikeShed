package borg.trikeshed.num

import borg.trikeshed.lib.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BigIntTest {

    @Test
    fun testConstructorInt() {
        val zero = BigInt(0)
        assertEquals(null, zero.sign)
        assertEquals(listOf(), zero.magnitude.toList())

        val positive = BigInt(42)
        assertEquals(true, positive.sign)
        assertEquals(listOf(42u), positive.magnitude.toList())

        val negative = BigInt(-42)
        assertEquals(false, negative.sign)
        assertEquals(listOf(42u), negative.magnitude.toList())
    }

    @Test
    fun testConstructorLong() {
        val zero = BigInt(0L)
        assertEquals(null, zero.sign)
        assertEquals(listOf(), zero.magnitude.toList())

        val positive = BigInt(42L)
        assertEquals(true, positive.sign)
        assertEquals(listOf(42u), positive.magnitude.toList())

        val negative = BigInt(-42L)
        assertEquals(false, negative.sign)
        assertEquals(listOf(42u), negative.magnitude.toList())
    }

    @Test
    fun testConstructorString() {
        val zero = BigInt("0")
        assertEquals(null, zero.sign)
        assertEquals(listOf(), zero.magnitude.toList())

        val positive = BigInt("42")
        assertEquals(true, positive.sign)
        assertEquals(listOf(42u), positive.magnitude.toList())

        val negative = BigInt("-42")
        assertEquals(false, negative.sign)
        assertEquals(listOf(42u), negative.magnitude.toList())

        assertFailsWith<NumberFormatException> {
            BigInt("invalid")
        }
    }

    @Test
    fun testConstructorULong() {
        val zero = BigInt(0UL)
        assertEquals(null, zero.sign)
        assertEquals(listOf(), zero.magnitude.toList())

        val positive = BigInt(42UL)
        assertEquals(true, positive.sign)
        assertEquals(listOf(42u), positive.magnitude.toList())
    }

    @Test
    fun testCompareTo() {
        val a = BigInt(42)
        val b = BigInt(-42)
        val c = BigInt(42L)
        val d = BigInt("42")

        assertTrue(a.compareTo(b) > 0)
        assertTrue(b.compareTo(a) < 0)
        assertTrue(a.compareTo(c) == 0)
        assertTrue(a.compareTo(d) == 0)
    }

    @ExperimentalUnsignedTypes
    @Test
    fun testToUByteArray() {
        val zero = BigInt(0)
        val zeroBytes = zero.toUByteArray()
        assertEquals(1, zeroBytes.size)
        assertEquals(0u, zeroBytes[0])

        val positive = BigInt(42)
        val positiveBytes = positive.toUByteArray()
        assertEquals(3, positiveBytes.size)
        assertEquals(1u, positiveBytes[0])
        assertEquals(0u, positiveBytes[1])
        assertEquals(42u, positiveBytes[2])

        val negative = BigInt(-42)
        val negativeBytes = negative.toUByteArray()
        assertEquals(3, negativeBytes.size)
        assertEquals(255u, negativeBytes[0])
        assertEquals(0u, negativeBytes[1])
        assertEquals(42u, negativeBytes[2])
    }
}
