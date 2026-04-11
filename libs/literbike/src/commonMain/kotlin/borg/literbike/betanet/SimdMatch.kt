package borg.literbike.betanet

/**
 * SIMD-first detection scaffold (naive u64 scan as a fast path)
 *
 * This module provides a small policy wrapper that prefers a simulated SIMD path
 * when AVX2 is reported available by `hasAvx2()`; falls back to the scalar
 * `ProtocolDetector` otherwise. This is intentionally small and testable.
 * Ported from literbike/src/betanet/simd_match.rs
 */

/**
 * Detect with policy: uses SIMD fast path when AVX2 is available,
 * otherwise falls back to scalar ProtocolDetector.
 */
fun detectWithPolicy(anchors: List<Anchor>, data: ByteArray): Anchor? {
    return if (hasAvx2()) {
        simdDetect(anchors, data)
    } else {
        ProtocolDetector(anchors).detect(data)
    }
}

private fun simdDetect(anchors: List<Anchor>, data: ByteArray): Anchor? {
    // Naive fast-path: compare 8-byte words (big-endian) against anchor patterns.
    // Keeps the semantics of priority resolution.
    if (data.size < 8) return null

    var best: Anchor? = null

    for (i in 0 until data.size - 7) {
        val window = data.copyOfRange(i, i + 8)
        for (a in anchors) {
            if (a.matches(window)) {
                if (best == null) {
                    best = a
                } else if (a.priority > best.priority) {
                    best = a
                }
            }
        }
    }

    return best
}
