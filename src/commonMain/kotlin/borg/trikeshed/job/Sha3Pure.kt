package borg.trikeshed.job

import borg.trikeshed.userspace.nio.platform.spi.*

/**
 * FIPS 202 SHA-3: the Keccak-f[1600] permutation under a sponge of rate 200 - 2 * digest bytes.
 * Lanes are little endian as the standard defines; [littleEndianGetLongAt] moves them per platform.
 */
object Sha3Pure {
    /** ι round constants RC[0..23]. */
    val RC = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AuL.toLong(), 0x8000000080008000uL.toLong(),
        0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, 0x800000000000008BuL.toLong(), 0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(), 0x000000000000800AL, 0x800000008000000AuL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(), 0x0000000080000001L, 0x8000000080008008uL.toLong(),
    )

    /** ρ rotation of each lane visited along the π cycle that starts at lane 1. */
    val RHO = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)

    /** π cycle: lane x + 5y moves to lane y + 5(2x + 3y mod 5), visited from lane 1. */
    val PI = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)

    /** Keccak-f[1600] over the 25 lanes of [a]; [c] is five lanes of scratch. */
    fun permute(a: LongArray, c: LongArray) {
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
            }
            var lane = a[1]
            for (i in 0 until 24) {
                val j = PI[i]
                val next = a[j]
                a[j] = lane.rotateLeft(RHO[i])
                lane = next
            }
            for (y in 0 until 25 step 5) {
                for (x in 0 until 5) c[x] = a[y + x]
                for (x in 0 until 5) a[y + x] = c[x] xor (c[(x + 1) % 5].inv() and c[(x + 2) % 5])
            }
            a[0] = a[0] xor RC[round]
        }
    }

    /** SHA3-256 of [input]. */
    fun digest(input: ByteArray): ByteArray = Sha3(32).update(input).digest()
}

/** Incremental SHA-3 of [size] digest bytes (28, 32, 48 or 64) over [Sha3Pure.permute]. */
class Sha3(val size: Int = 32) {
    val rate = 200 - 2 * size
    val lanes = LongArray(25)
    val scratch = LongArray(5)
    val block = ByteArray(rate)
    var filled = 0

    init {
        require(size == 28 || size == 32 || size == 48 || size == 64) { "SHA-3 digest size $size" }
    }

    fun absorb(src: ByteArray, at: Int) {
        for (i in 0 until rate / 8) lanes[i] = lanes[i] xor src.littleEndianGetLongAt(at + 8 * i)
        if (rate % 8 != 0) { // SHA3-224: the 18th lane is half covered
            val i = rate / 8
            var tail = 0L
            for (k in 0 until rate % 8) tail = tail or ((src[at + 8 * i + k].toLong() and 0xFF) shl (8 * k))
            lanes[i] = lanes[i] xor tail
        }
        Sha3Pure.permute(lanes, scratch)
    }

    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Sha3 {
        var at = offset
        val end = offset + count
        if (filled > 0) {
            val take = minOf(rate - filled, end - at)
            bytes.copyInto(block, filled, at, at + take)
            filled += take
            at += take
            if (filled < rate) return this
            absorb(block, 0)
            filled = 0
        }
        while (end - at >= rate) {
            absorb(bytes, at)
            at += rate
        }
        bytes.copyInto(block, 0, at, end)
        filled = end - at
        return this
    }

    /** The digest of everything updated so far; the instance is spent afterwards. */
    fun digest(): ByteArray {
        block.fill(0, filled, rate)
        block[filled] = (block[filled].toInt() xor 0x06).toByte()
        block[rate - 1] = (block[rate - 1].toInt() xor 0x80).toByte()
        absorb(block, 0)
        val out = ByteArray(size)
        for (k in 0 until size) out[k] = (lanes[k / 8] ushr (8 * (k % 8))).toByte()
        return out
    }
}
