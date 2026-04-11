package borg.literbike.rbcursive.simd

import borg.literbike.rbcursive.*

/**
 * NEON SIMD scanner stub for Kotlin/JVM.
 * Ported from literbike/src/rbcursive/simd/neon.rs.
 *
 * Note: JVM doesn't have direct NEON intrinsics, so this delegates to GenericScanner.
 * On the JVM, use GenericScanner or AutovecScanner for best performance.
 */
class NeonScanner : SimdScanner {
    private val delegate = GenericScanner()

    companion object {
        fun new(): NeonScanner = NeonScanner()
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
        name = "NEON (JVM stub)",
        vectorBits = 0,
        estimatedThroughputGbps = 0.05,
        supportsGather = false,
        supportsPopcount = false
    )
}
