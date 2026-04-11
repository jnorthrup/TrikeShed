package borg.literbike.rbcursive.simd

import borg.literbike.rbcursive.*

/**
 * SSE2 SIMD scanner stub for Kotlin/JVM.
 * Ported from literbike/src/rbcursive/simd/sse2.rs.
 *
 * Note: JVM doesn't have direct SSE2 intrinsics, so this delegates to GenericScanner.
 * On the JVM, use GenericScanner or AutovecScanner for best performance.
 */
class Sse2Scanner : SimdScanner {
    private val delegate = GenericScanner()

    companion object {
        fun new(): Sse2Scanner = Sse2Scanner()
    }

    override fun scanBytes(data: ByteArray, targets: ByteArray): List<Int> =
        delegate.scanBytes(data, targets)

    override fun scanStructural(data: ByteArray): List<Int> =
        delegate.scanStructural(data)

    override fun scanQuotes(data: ByteArray): List<Int> =
        delegate.scanQuotes(data)

    override fun scanAnyByte(data: ByteArray, targets: ByteArray): List<Int> =
        delegate.scanAnyByte(data, targets)

    override fun gatherBytes(data: ByteArray, positions: List<Int>): ByteArray =
        delegate.gatherBytes(data, positions)

    override fun popcount(bitmap: List<Int>): Int =
        delegate.popcount(bitmap)

    override fun capabilities(): ScannerCapabilities = ScannerCapabilities(
        name = "SSE2 (JVM stub)",
        vectorBits = 0,
        estimatedThroughputGbps = 0.05,
        supportsGather = false,
        supportsPopcount = false
    )
}
