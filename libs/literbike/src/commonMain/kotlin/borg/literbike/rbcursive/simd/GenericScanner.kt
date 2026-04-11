package borg.literbike.rbcursive.simd

/**
 * SIMD implementations for different architectures.
 * Ported from literbike/src/rbcursive/simd/mod.rs.
 */

import borg.literbike.rbcursive.SimdScanner
import borg.literbike.rbcursive.ScalarScanner
import borg.literbike.rbcursive.AutovecScanner
import borg.literbike.rbcursive.ScannerCapabilities

/**
 * SIMD feature detection for runtime capability discovery.
 */
data class SimdCapabilities(
    val hasNeon: Boolean = false,
    val hasAvx2: Boolean = false,
    val hasSse2: Boolean = false,
    val maxVectorBits: Int = 0
) {
    companion object {
        fun detect(): SimdCapabilities {
            val arch = System.getProperty("os.arch").lowercase()
            return when {
                "aarch64" in arch || "arm64" in arch -> SimdCapabilities(
                    hasNeon = true, maxVectorBits = 128
                )
                "amd64" in arch || "x86_64" in arch -> SimdCapabilities(
                    hasSse2 = true, // SSE2 is baseline for x86_64
                    hasAvx2 = detectAvx2(),
                    maxVectorBits = if (detectAvx2()) 256 else 128
                )
                else -> SimdCapabilities(maxVectorBits = 0)
            }
        }

        private fun detectAvx2(): Boolean {
            // On JVM, we can't directly detect AVX2 at runtime easily.
            // This would require JNI or JNA to call CPUID.
            // For now, assume AVX2 is available on x86_64.
            val arch = System.getProperty("os.arch").lowercase()
            return "amd64" in arch || "x86_64" in arch
        }
    }

    fun bestScannerName(): String = when {
        hasAvx2 -> "AVX2"
        hasNeon -> "NEON"
        hasSse2 -> "SSE2"
        else -> "Generic"
    }

    fun estimatedThroughputGbps(): Double = when (maxVectorBits) {
        256 -> 3.0  // AVX2
        128 -> if (hasNeon) 4.0 else 1.5  // NEON or SSE2
        else -> 0.1  // Generic
    }
}

/**
 * Create the best SIMD scanner for the current platform.
 */
fun createOptimalScanner(): SimdScanner {
    val caps = SimdCapabilities.detect()
    return when {
        caps.hasAvx2 -> GenericScanner() // On JVM, use generic with auto-vectorization
        caps.hasNeon -> GenericScanner()
        caps.hasSse2 -> GenericScanner()
        else -> ScalarScanner()
    }
}

/**
 * Benchmark all available scanners.
 */
fun benchmarkAllScanners(data: ByteArray): List<Pair<String, Double>> {
    val results = mutableListOf<Pair<String, Double>>()

    val generic = GenericScanner()
    results.add("Generic" to benchmarkScanner(generic, data))

    val scalar = ScalarScanner()
    results.add("Scalar" to benchmarkScanner(scalar, data))

    val autovec = AutovecScanner()
    results.add("Autovec" to benchmarkScanner(autovec, data))

    return results
}

private fun benchmarkScanner(scanner: SimdScanner, data: ByteArray): Double {
    val iterations = 100
    val start = System.nanoTime()
    repeat(iterations) { scanner.scanStructural(data) }
    val elapsed = System.nanoTime() - start
    val dataSizeMb = data.size.toDouble() / 1024.0 / 1024.0
    return (dataSizeMb * iterations) / (elapsed / 1e9) / 1024.0
}

/**
 * Generic scanner with auto-vectorization hints.
 * Ported from literbike/src/rbcursive/simd/generic.rs.
 */
class GenericScanner : SimdScanner {
    companion object {
        private val STRUCTURAL: ByteArray = "{}[](),:;\" \t\r\n".toByteArray()
        private const val CHUNK_SIZE = 64
    }

    override fun scanBytes(data: ByteArray, targets: ByteArray): List<Int> {
        return if (targets.size == 1) {
            scanSingleByte(data, targets[0])
        } else {
            scanMultipleBytes(data, targets)
        }
    }

    override fun scanStructural(data: ByteArray): List<Int> = scanMultipleBytes(data, STRUCTURAL)

    override fun scanQuotes(data: ByteArray): List<Int> = scanSingleByte(data, '"'.code.toByte())

    override fun scanAnyByte(data: ByteArray, targets: ByteArray): List<Int> = scanBytes(data, targets)

    override fun gatherBytes(data: ByteArray, positions: List<Int>): ByteArray {
        return positions.mapNotNull { pos -> data.getOrNull(pos) }.toByteArray()
    }

    override fun popcount(bitmap: List<Int>): Int = bitmap.sumOf { it.countOneBits() }

    override fun capabilities(): ScannerCapabilities = ScannerCapabilities(
        name = "Generic",
        vectorBits = 0,
        estimatedThroughputGbps = 0.1,
        supportsGather = true,
        supportsPopcount = true
    )

    private fun scanSingleByte(data: ByteArray, target: Byte): List<Int> {
        val positions = mutableListOf<Int>()
        var i = 0
        while (i + CHUNK_SIZE <= data.size) {
            for (j in 0 until CHUNK_SIZE) {
                if (data[i + j] == target) positions.add(i + j)
            }
            i += CHUNK_SIZE
        }
        while (i < data.size) {
            if (data[i] == target) positions.add(i)
            i++
        }
        return positions
    }

    private fun scanMultipleBytes(data: ByteArray, targets: ByteArray): List<Int> {
        val lookup = BooleanArray(256)
        for (t in targets) lookup[t.toInt() and 0xFF] = true

        val positions = mutableListOf<Int>()
        var i = 0
        while (i + CHUNK_SIZE <= data.size) {
            for (j in 0 until CHUNK_SIZE) {
                if (lookup[data[i + j].toInt() and 0xFF]) positions.add(i + j)
            }
            i += CHUNK_SIZE
        }
        while (i < data.size) {
            if (lookup[data[i].toInt() and 0xFF]) positions.add(i)
            i++
        }
        return positions
    }
}
