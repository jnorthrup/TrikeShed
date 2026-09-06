/*
 * TrikeShed test for the ported Tessera GF(256) kernels (tessera/NOTICE.md). The contract is upstream's:
 * every kernel must produce bytes identical to Scalar, the oracle.
 */
package tessera.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Gf256KernelTest {
    @Test fun tablesAreAField() {
        for (a in 1..255) {
            assertEquals(1, GF256.mul(a, GF256.inv(a)), "a * a^-1 for $a")
            assertEquals(a, GF256.mul(a, 1))
            assertEquals(0, GF256.mul(a, 0))
        }
        // x * x = x^2 = 4 under the polynomial, and 0x80 * 2 reduces by 0x11D
        assertEquals(4, GF256.mul(2, 2))
        assertEquals(0x1D, GF256.mul(0x80, 2))
    }

    @Test fun swarMatchesScalarAtEveryLengthAndCoefficient() {
        val rnd = Random(7)
        for (n in 0..300) {
            val src = ByteArray(n) { rnd.nextInt(256).toByte() }
            val seed = ByteArray(n) { rnd.nextInt(256).toByte() }
            for (c in 0..255) {
                val a = seed.copyOf(); GF256.Scalar.mulAddInto(a, src, c)
                val b = seed.copyOf(); GF256.Swar.mulAddInto(b, src, c)
                assertContentEquals(a, b, "n=$n c=$c")
            }
        }
    }

    @Test fun swarPerLaneIsTheTableProduct() {
        // the lanes must not leak into each other: put the same coefficient across mixed bytes
        val src = ByteArray(8) { (it * 37 + 0x81).toByte() }
        for (c in listOf(1, 2, 3, 0x80, 0xFF, 0x1D)) {
            val dst = ByteArray(8); GF256.Swar.mulAddInto(dst, src, c)
            for (i in 0 until 8) assertEquals(GF256.mul(src[i].toInt() and 0xFF, c), dst[i].toInt() and 0xFF, "lane $i c=$c")
        }
    }

    @Test fun swarIsMeasuredAgainstScalarOnTheHotShape() {
        // Not a timing assertion (CI hosts vary); the numbers are printed for the record and the
        // kernels are asserted equal on the same input, which is the property that matters.
        val rnd = Random(11)
        val src = ByteArray(1200) { rnd.nextInt(256).toByte() }
        val a = ByteArray(1200); val b = ByteArray(1200)
        var t0 = kotlin.time.TimeSource.Monotonic.markNow()
        repeat(20_000) { GF256.Scalar.mulAddInto(a, src, 1 + (it and 0xFE)) }
        val scalar = t0.elapsedNow()
        t0 = kotlin.time.TimeSource.Monotonic.markNow()
        repeat(20_000) { GF256.Swar.mulAddInto(b, src, 1 + (it and 0xFE)) }
        val swar = t0.elapsedNow()
        println("GF256 mulAddInto 1200 B x 20k: scalar=$scalar swar=$swar")
        assertContentEquals(a, b)
    }
}
