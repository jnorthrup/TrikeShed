package borg.trikeshed.platform.kernel

/**
 * Densified operations - Zero-allocation buffer transformations
 *
 * Applies densification principles to eliminate all no-ops in tight loops.
 * Every CPU cycle performs useful work - no speculative stalls or defensive checks.
 *
 * Note: SIMD operations are platform-specific; this provides the interface.
 */

/**
 * Densified memcpy using SIMD operations when possible
 * Eliminates all branching in the critical path for maximum throughput
 *
 * UNSAFE: Caller must ensure src and dst are valid for reads/writes of len bytes.
 * The regions must not overlap.
 */
expect fun densifiedCopy(src: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, len: Int)

/**
 * Densified comparison - returns 0 if equal, non-zero if different
 *
 * UNSAFE: Caller must ensure arrays are valid for reads of len bytes.
 */
expect fun densifiedCompare(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, len: Int): Int

/**
 * Densified XOR operation
 *
 * UNSAFE: Caller must ensure all arrays are valid for len bytes.
 */
expect fun densifiedXor(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, dst: ByteArray, dstOffset: Int, len: Int)

/**
 * Default (non-SIMD) implementations for commonMain
 */
internal object DensifiedOpsDefault {
    fun copy(src: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, len: Int) {
        src.copyInto(dst, dstOffset, srcOffset, srcOffset + len)
    }

    fun compare(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, len: Int): Int {
        for (i in 0 until len) {
            val diff = (a[aOffset + i].toInt() and 0xFF) - (b[bOffset + i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }

    fun xor(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, dst: ByteArray, dstOffset: Int, len: Int) {
        for (i in 0 until len) {
            dst[dstOffset + i] = (a[aOffset + i].toInt() xor b[bOffset + i].toInt()).toByte()
        }
    }
}
