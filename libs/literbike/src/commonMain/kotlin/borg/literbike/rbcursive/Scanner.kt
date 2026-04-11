package borg.literbike.rbcursive

/**
 * RBCursive SIMD Scanner - Core scanning trait and implementations.
 * Port of BBCursive SIMD scanning functionality.
 * Ported from literbike/src/rbcursive/scanner.rs.
 */

/**
 * Core SIMD scanner interface - BBCursive-style pattern scanning.
 */
interface SimdScanner {
    /** Scan for all occurrences of target bytes */
    fun scanBytes(data: ByteArray, targets: ByteArray): List<Int>

    /** Scan for structural characters (JSON/HTTP delimiters) */
    fun scanStructural(data: ByteArray): List<Int>

    /** Scan for quote characters */
    fun scanQuotes(data: ByteArray): List<Int>

    /** Scan for any of multiple target bytes */
    fun scanAnyByte(data: ByteArray, targets: ByteArray): List<Int>

    /** Gather bytes at specific positions */
    fun gatherBytes(data: ByteArray, positions: List<Int>): ByteArray

    /** Population count (count set bits in bitmap) */
    fun popcount(bitmap: List<Int>): Int

    /** Get scanner capabilities */
    fun capabilities(): ScannerCapabilities
}

/**
 * Scanner capabilities and performance characteristics.
 */
data class ScannerCapabilities(
    val name: String,
    val vectorBits: Int,
    val estimatedThroughputGbps: Double,
    val supportsGather: Boolean,
    val supportsPopcount: Boolean
)

/**
 * Scalar fallback scanner - pure Kotlin implementation.
 */
class ScalarScanner : SimdScanner {
    companion object {
        private val STRUCTURAL: ByteArray = "{}[](),:;\" \t\r\n".toByteArray()
    }

    override fun scanBytes(data: ByteArray, targets: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        if (targets.size == 1) {
            val target = targets[0]
            for ((i, byte) in data.withIndex()) {
                if (byte == target) positions.add(i)
            }
        } else {
            val targetSet = targets.toSet()
            for ((i, byte) in data.withIndex()) {
                if (byte in targetSet) positions.add(i)
            }
        }
        return positions
    }

    override fun scanStructural(data: ByteArray): List<Int> = scanBytes(data, STRUCTURAL)

    override fun scanQuotes(data: ByteArray): List<Int> = scanBytes(data, byteArrayOf('"'.code.toByte()))

    override fun scanAnyByte(data: ByteArray, targets: ByteArray): List<Int> = scanBytes(data, targets)

    override fun gatherBytes(data: ByteArray, positions: List<Int>): ByteArray {
        return positions.mapNotNull { pos -> data.getOrNull(pos) }.toByteArray()
    }

    override fun popcount(bitmap: List<Int>): Int = bitmap.sumOf { it.countOneBits() }

    override fun capabilities(): ScannerCapabilities = ScannerCapabilities(
        name = "Scalar",
        vectorBits = 0,
        estimatedThroughputGbps = 0.05,
        supportsGather = true,
        supportsPopcount = true
    )
}

/**
 * Auto-vectorization scanner - optimized for compiler auto-vectorization.
 */
class AutovecScanner : SimdScanner {
    companion object {
        private val STRUCTURAL: ByteArray = "{}[](),:;\" \t\r\n".toByteArray()
    }

    override fun scanBytes(data: ByteArray, targets: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        if (targets.size == 1) {
            val target = targets[0]
            for (i in data.indices) {
                if (data[i] == target) positions.add(i)
            }
        } else {
            val lookup = BooleanArray(256)
            for (t in targets) lookup[t.toInt() and 0xFF] = true
            for (i in data.indices) {
                if (lookup[data[i].toInt() and 0xFF]) positions.add(i)
            }
        }
        return positions
    }

    override fun scanStructural(data: ByteArray): List<Int> {
        val isStructural = BooleanArray(256)
        for (b in STRUCTURAL) isStructural[b.toInt() and 0xFF] = true
        val positions = mutableListOf<Int>()
        for (i in data.indices) {
            if (isStructural[data[i].toInt() and 0xFF]) positions.add(i)
        }
        return positions
    }

    override fun scanQuotes(data: ByteArray): List<Int> = scanBytes(data, byteArrayOf('"'.code.toByte()))

    override fun scanAnyByte(data: ByteArray, targets: ByteArray): List<Int> = scanBytes(data, targets)

    override fun gatherBytes(data: ByteArray, positions: List<Int>): ByteArray {
        val result = mutableListOf<Byte>()
        for (pos in positions) {
            if (pos < data.size) result.add(data[pos])
        }
        return result.toByteArray()
    }

    override fun popcount(bitmap: List<Int>): Int = bitmap.sumOf { it.countOneBits() }

    override fun capabilities(): ScannerCapabilities = ScannerCapabilities(
        name = "Autovec",
        vectorBits = 128,
        estimatedThroughputGbps = 0.5,
        supportsGather = true,
        supportsPopcount = true
    )
}

/**
 * Benchmarking utilities for scanner performance.
 */
class ScannerBenchmark(
    private val scanner: SimdScanner,
    private val dataSizeMb: Double
) {
    companion object {
        fun new(scanner: SimdScanner, data: ByteArray): ScannerBenchmark {
            return ScannerBenchmark(scanner, data.size.toDouble() / 1024.0 / 1024.0)
        }
    }

    fun benchmarkStructuralScan(data: ByteArray, iterations: Int): BenchmarkResult {
        val start = System.nanoTime()
        repeat(iterations) { scanner.scanStructural(data) }
        val elapsed = System.nanoTime() - start
        val throughputGbps = (dataSizeMb * iterations) / (elapsed / 1e9) / 1024.0

        return BenchmarkResult(
            operation = "Structural Scan",
            iterations = iterations,
            elapsedNanos = elapsed,
            throughputGbps = throughputGbps,
            capabilities = scanner.capabilities()
        )
    }

    fun benchmarkQuoteScan(data: ByteArray, iterations: Int): BenchmarkResult {
        val start = System.nanoTime()
        repeat(iterations) { scanner.scanQuotes(data) }
        val elapsed = System.nanoTime() - start
        val throughputGbps = (dataSizeMb * iterations) / (elapsed / 1e9) / 1024.0

        return BenchmarkResult(
            operation = "Quote Scan",
            iterations = iterations,
            elapsedNanos = elapsed,
            throughputGbps = throughputGbps,
            capabilities = scanner.capabilities()
        )
    }
}

data class BenchmarkResult(
    val operation: String,
    val iterations: Int,
    val elapsedNanos: Long,
    val throughputGbps: Double,
    val capabilities: ScannerCapabilities
) {
    fun printSummary() {
        println("=== $operation Benchmark ===")
        println("Scanner: ${capabilities.name}")
        println("Vector bits: ${capabilities.vectorBits}")
        println("Iterations: $iterations")
        println("Elapsed: ${elapsedNanos / 1_000_000}ms")
        println("Throughput: ${"%.2f".format(throughputGbps)} GB/s")
        println("Estimated: ${"%.2f".format(capabilities.estimatedThroughputGbps)} GB/s")
    }
}

/**
 * Create SIMD scanner for given strategy.
 */
fun createSimdScanner(strategy: ScanStrategy): SimdScanner = when (strategy) {
    ScanStrategy.Scalar -> ScalarScanner()
    ScanStrategy.Autovec -> AutovecScanner()
    ScanStrategy.Simd -> AutovecScanner() // JVM doesn't have direct SIMD intrinsics
}

/**
 * SIMD strategy selection.
 */
enum class ScanStrategy {
    Scalar,
    Simd,
    Autovec
}
