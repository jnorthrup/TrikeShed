package borg.trikeshed.btrfs

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * P = XOR(data); Q = sum(2^lane * data) in GF(256), polynomial 0x11d.
 * Coefficients match the syndrome definition in Linux v6.12 lib/raid6/int.uc:
 * https://github.com/torvalds/linux/blob/v6.12/lib/raid6/int.uc
 *
 * Recovery handles known erasures. Detecting silent corruption requires an
 * independent checksum; this codec must not guess which unmarked byte is wrong.
 */
object BtrfsRaidParity {
    private val exponents = IntArray(510).also { table ->
        var value = 1
        for (index in 0 until 255) {
            table[index] = value
            value = value shl 1
            if (value and 0x100 != 0) value = value xor 0x11d
        }
        for (index in 255 until table.size) table[index] = table[index - 255]
    }
    private val logarithms = IntArray(256).also { table ->
        for (index in 0 until 255) table[exponents[index]] = index
    }

    fun encode(data: Array<ByteArray>): Join<ByteArray, ByteArray> {
        require(data.size in 1..255) { "P/Q requires 1..255 data lanes" }
        val blockSize = data[0].size
        require(data.all { it.size == blockSize }) { "Data block sizes differ" }
        val p = ByteArray(blockSize)
        val q = ByteArray(blockSize)
        for (lane in data.indices) {
            val coefficient = exponents[lane]
            for (offset in 0 until blockSize) {
                val value = data[lane][offset].toInt() and 0xff
                p[offset] = (p[offset].toInt() xor value).toByte()
                q[offset] = (q[offset].toInt() xor multiply(coefficient, value)).toByte()
            }
        }
        return p j q
    }

    /** Reconstruct missing data without modifying or aliasing supplied blocks. */
    fun recover(
        data: Array<ByteArray?>,
        p: ByteArray?,
        q: ByteArray?,
        blockSize: Int,
    ): Array<ByteArray> {
        require(data.size in 1..255) { "P/Q requires 1..255 data lanes" }
        require(blockSize >= 0) { "Negative block size" }
        require(data.all { it == null || it.size == blockSize }) { "Data block sizes differ" }
        require(p == null || p.size == blockSize) { "P block size differs" }
        require(q == null || q.size == blockSize) { "Q block size differs" }
        val missing = IntArray(2)
        var missingCount = 0
        for (lane in data.indices) if (data[lane] == null) {
            require(missingCount < 2) { "More than two data erasures" }
            missing[missingCount++] = lane
        }
        require(missingCount <= (if (p == null) 0 else 1) + (if (q == null) 0 else 1)) {
            "Insufficient parity for data erasures"
        }
        val result = Array(data.size) { data[it]?.copyOf() ?: ByteArray(blockSize) }
        if (missingCount == 0) return result

        val first = missing[0]
        val firstCoefficient = exponents[first]
        for (offset in 0 until blockSize) {
            var pResidual = p?.get(offset)?.toInt()?.and(0xff) ?: 0
            var qResidual = q?.get(offset)?.toInt()?.and(0xff) ?: 0
            for (lane in data.indices) {
                val block = data[lane] ?: continue
                val value = block[offset].toInt() and 0xff
                pResidual = pResidual xor value
                qResidual = qResidual xor multiply(exponents[lane], value)
            }
            if (missingCount == 1) {
                result[first][offset] = if (p != null) pResidual.toByte()
                else divide(qResidual, firstCoefficient).toByte()
            } else {
                val second = missing[1]
                val secondCoefficient = exponents[second]
                val firstValue = divide(
                    qResidual xor multiply(secondCoefficient, pResidual),
                    firstCoefficient xor secondCoefficient,
                )
                result[first][offset] = firstValue.toByte()
                result[second][offset] = (pResidual xor firstValue).toByte()
            }
        }
        return result
    }

    private fun multiply(left: Int, right: Int): Int =
        if (left == 0 || right == 0) 0 else exponents[logarithms[left] + logarithms[right]]

    private fun divide(numerator: Int, denominator: Int): Int {
        require(denominator != 0) { "Singular parity coefficients" }
        return if (numerator == 0) 0
        else exponents[(logarithms[numerator] - logarithms[denominator] + 255) % 255]
    }
}
