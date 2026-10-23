package borg.trikeshed.skinnerbox

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent arbitrary-precision oracle for the common checked rational implementation. */
class MeshPriceOracleTest {
    @Test fun exactArithmeticAgreesWithBigIntegerAcrossDifferentScales() {
        val random = Random(0x4e33)
        repeat(2000) {
            val an = random.nextLong(0, 1_000_000)
            val ad = random.nextLong(1, 1_000_000)
            val bn = random.nextLong(0, 1_000_000)
            val bd = random.nextLong(1, 1_000_000)
            val a = MeshPrice.of(an, ad)
            val b = MeshPrice.of(bn, bd)
            val left = BigInteger.valueOf(an).multiply(BigInteger.valueOf(bd))
            val right = BigInteger.valueOf(bn).multiply(BigInteger.valueOf(ad))
            assertEquals(left.compareTo(right).coerceIn(-1, 1), a.compareTo(b).coerceIn(-1, 1))
            val numerator = left.add(right)
            val denominator = BigInteger.valueOf(ad).multiply(BigInteger.valueOf(bd))
            val common = numerator.gcd(denominator)
            val sum = a + b
            assertEquals(numerator.divide(common).longValueExact(), sum.numerator)
            assertEquals(denominator.divide(common).longValueExact(), sum.denominator)
            val rounded = numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator)
            assertEquals(rounded.longValueExact(), sum.ceilingMicroUsd())
        }
    }

    @Test fun storageCancelsUnitsBeforeMultiplyingLargeFactors() {
        val unit = Long.MAX_VALUE
        val rate = MeshStorageRate(unit, unit, unit)
        val actual = rate.cost(unit, unit)
        val big = BigInteger.valueOf(unit)
        val oracle = big.pow(3).divide(big.pow(2))
        assertEquals(oracle.longValueExact(), actual.numerator)
        assertEquals(1L, actual.denominator)
    }
}
