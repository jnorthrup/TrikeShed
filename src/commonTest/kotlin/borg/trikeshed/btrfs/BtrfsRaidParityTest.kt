package borg.trikeshed.btrfs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class BtrfsRaidParityTest {
    @Test
    fun fixed_syndrome_vector_includes_field_reduction() {
        val data = arrayOf(
            byteArrayOf(0, 1, 0x80.toByte(), 0xff.toByte()),
            byteArrayOf(2, 4, 0x80.toByte(), 0xff.toByte()),
            byteArrayOf(3, 5, 0x80.toByte(), 0xff.toByte()),
        )
        val parity = BtrfsRaidParity.encode(data)
        assertContentEquals(byteArrayOf(1, 0, 0x80.toByte(), 0xff.toByte()), parity.a)
        assertContentEquals(byteArrayOf(8, 29, 0xa7.toByte(), 0xc7.toByte()), parity.b)
    }

    @Test
    fun syndrome_matches_independent_polynomial_arithmetic() {
        val data = Array(255) { lane -> ByteArray(256) { offset -> (lane * 61 + offset).toByte() } }
        val parity = BtrfsRaidParity.encode(data)
        val expected = polynomialParity(data)
        assertContentEquals(expected.first, parity.a)
        assertContentEquals(expected.second, parity.b)
    }

    @Test
    fun every_single_and_double_member_erasure_uses_independent_parity() {
        val data = Array(11) { lane -> ByteArray(257) { offset -> (lane * 37 + offset * 23).toByte() } }
        val parity = polynomialParity(data)
        // Enumerate failures among data lanes plus P and Q, including both parity lanes.
        for (first in 0 until data.size + 2) {
            verifyRecovery(data, parity, setOf(first))
            for (second in first + 1 until data.size + 2) verifyRecovery(data, parity, setOf(first, second))
        }
    }

    @Test
    fun extreme_coefficients_and_nonaliased_inputs() {
        val data = Array(255) { lane -> ByteArray(9) { offset -> (lane + offset * 31).toByte() } }
        val parity = polynomialParity(data)
        verifyRecovery(data, parity, setOf(0, 254))
        verifyRecovery(data, parity, setOf(254, 255)) // Highest data coefficient, Q alone.
        val source = arrayOf<ByteArray?>(byteArrayOf(1, 2, 3))
        val recovered = BtrfsRaidParity.recover(source, null, null, 3)
        assertNotSame(source[0], recovered[0])
        recovered[0][0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), source[0])
    }

    @Test
    fun insufficient_or_malformed_syndromes_are_rejected() {
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.encode(emptyArray()) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.encode(Array(256) { ByteArray(1) }) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.encode(arrayOf(ByteArray(1), ByteArray(2))) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.recover(arrayOf(null), null, null, 1) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.recover(arrayOf(null, null), ByteArray(1), null, 1) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.recover(arrayOf(null, null, null), ByteArray(1), ByteArray(1), 1) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.recover(arrayOf(ByteArray(2)), null, null, 1) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.recover(arrayOf(null), ByteArray(2), null, 1) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidParity.recover(arrayOf(null), null, ByteArray(2), 1) }
    }

    private fun verifyRecovery(data: Array<ByteArray>, parity: Pair<ByteArray, ByteArray>, missing: Set<Int>) {
        val input = Array<ByteArray?>(data.size) { if (it in missing) null else data[it].copyOf() }
        val p = if (data.size in missing) null else parity.first.copyOf()
        val q = if (data.size + 1 in missing) null else parity.second.copyOf()
        val recovered = BtrfsRaidParity.recover(input, p, q, data[0].size)
        for (lane in data.indices) {
            assertContentEquals(data[lane], recovered[lane], "data lane $lane after member erasures $missing")
            if (input[lane] != null) assertContentEquals(data[lane], input[lane])
        }
        if (p != null) assertContentEquals(parity.first, p)
        if (q != null) assertContentEquals(parity.second, q)
    }

    /** Bitwise polynomial division, independent of the production logarithm tables. */
    private fun polynomialMultiply(left: Int, right: Int): Int {
        var polynomial = 0
        for (bit in 0..7) if (right and (1 shl bit) != 0) polynomial = polynomial xor (left shl bit)
        for (bit in 14 downTo 8) if (polynomial and (1 shl bit) != 0) polynomial = polynomial xor (0x11d shl (bit - 8))
        return polynomial
    }

    private fun polynomialParity(data: Array<ByteArray>): Pair<ByteArray, ByteArray> {
        val p = ByteArray(data[0].size)
        val q = ByteArray(data[0].size)
        var coefficient = 1
        for (block in data) {
            for (offset in block.indices) {
                val value = block[offset].toInt() and 255
                p[offset] = (p[offset].toInt() xor value).toByte()
                q[offset] = (q[offset].toInt() xor polynomialMultiply(coefficient, value)).toByte()
            }
            coefficient = polynomialMultiply(coefficient, 2)
        }
        return p to q
    }
}
