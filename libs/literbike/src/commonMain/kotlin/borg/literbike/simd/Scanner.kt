package borg.literbike.simd

/**
 * SIMD-accelerated protocol scanning for HTX with kernel EBPF JIT mapping
 * Ported from literbike/src/simd/scanner.rs
 *
 * Maps SIMD operations to kernel EBPF JIT targets for maximum performance.
 * Note: Kotlin/JVM doesn't have direct SIMD intrinsics; this provides
 * equivalent functionality with platform-appropriate implementations.
 */

/** SIMD scanner interface for protocol detection */
interface SimdScanner {
    /** Scan for structural characters using kernel EBPF JIT */
    fun scanStructural(data: ByteArray): List<Int>

    /** Scan for quote characters with vectorized kernel mapping */
    fun scanQuotes(data: ByteArray): List<Int>

    /** Scan for specific bytes using SIMD kernel operations */
    fun scanBytes(data: ByteArray, targets: ByteArray): List<Int>

    /** Get scanner capabilities including EBPF JIT support */
    fun capabilities(): ScannerCapabilities

    /** Get EBPF JIT target for kernel acceleration */
    fun ebpfJitTarget(): EbpfJitTarget?

    /** Execute vectorized operation using kernel EBPF */
    fun executeVectorOp(kernel: SimdKernel, target: EbpfJitTarget, data: ByteArray): List<Int>
}

data class ScannerCapabilities(
    val name: String,
    val vectorBits: Int,
    val estimatedThroughputGbps: Double,
    val ebpfJitEnabled: Boolean,
    val kernelAcceleration: KernelAcceleration,
    val simdInstructionSets: List<SimdInstructionSet>,
)

enum class KernelAcceleration {
    None,
    EbpfJit,
    XdpOffload,
    TcOffload,
    KprobeHook,
}

enum class SimdInstructionSet {
    Avx2,
    Avx512,
    Neon,
    Wasm128,
    RiscvVector,
}

/** Kernel EBPF JIT types */
typealias SimdKernel = (ByteArray) -> List<Int>

data class EbpfJitTarget(
    val instructionCount: Int,
    val bytecode: ByteArray,
    val executor: (ByteArray) -> List<Int>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EbpfJitTarget) return false
        return instructionCount == other.instructionCount &&
                bytecode.contentEquals(other.bytecode)
    }
    override fun hashCode(): Int = instructionCount xor bytecode.contentHashCode()
}

/** Generate EBPF bytecode for SIMD operations (simplified) */
private fun generateStructuralEbpf(): ByteArray {
    // Mock EBPF bytecode for structural character scanning
    return byteArrayOf(
        0x79.toByte(), 0x11.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x79.toByte(), 0x12.toByte(), 0x08.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        0xb7.toByte(), 0x00.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x95.toByte(), 0x00.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
}

/** Execute EBPF bytecode in kernel space (mock) */
private fun executeEbpfKernel(data: ByteArray): List<Int> {
    val positions = mutableListOf<Int>()
    for ((i, byte) in data.withIndex()) {
        when (byte) {
            '{'.code.toByte(), '}'.code.toByte(), '['.code.toByte(), ']'.code.toByte(),
            ':'.code.toByte(), ','.code.toByte() -> positions.add(i)
            else -> {}
        }
    }
    return positions
}

/** Generic scalar scanner fallback */
class ScalarScanner : SimdScanner {
    companion object {
        fun new(): ScalarScanner = ScalarScanner()
    }

    private val structuralTargets = byteArrayOf(
        '{'.code.toByte(), '}'.code.toByte(),
        '['.code.toByte(), ']'.code.toByte(),
        ':'.code.toByte(), ','.code.toByte(),
    )

    override fun scanStructural(data: ByteArray): List<Int> {
        ebpfJitTarget()?.let { target ->
            return executeVectorOp({ executeEbpfKernel(it) }, target, data)
        }

        // Fallback to scalar implementation
        val positions = mutableListOf<Int>()
        for ((i, byte) in data.withIndex()) {
            if (byte in structuralTargets) {
                positions.add(i)
            }
        }
        return positions
    }

    override fun scanQuotes(data: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        for ((i, byte) in data.withIndex()) {
            if (byte == '"'.code.toByte()) {
                positions.add(i)
            }
        }
        return positions
    }

    override fun scanBytes(data: ByteArray, targets: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        for ((i, byte) in data.withIndex()) {
            if (byte in targets) {
                positions.add(i)
            }
        }
        return positions
    }

    override fun capabilities(): ScannerCapabilities = ScannerCapabilities(
        name = "Scalar",
        vectorBits = 0,
        estimatedThroughputGbps = 0.1,
        ebpfJitEnabled = true,
        kernelAcceleration = KernelAcceleration.EbpfJit,
        simdInstructionSets = emptyList(),
    )

    override fun ebpfJitTarget(): EbpfJitTarget? {
        val bytecode = generateStructuralEbpf()
        return EbpfJitTarget(
            instructionCount = bytecode.size / 8,
            bytecode = bytecode,
            executor = ::executeEbpfKernel,
        )
    }

    override fun executeVectorOp(
        kernel: SimdKernel,
        target: EbpfJitTarget,
        data: ByteArray,
    ): List<Int> {
        return target.executor(data)
    }
}

/** Autovectorized scanner - relies on compiler optimizations */
class AutovecScanner : SimdScanner {
    companion object {
        fun new(): AutovecScanner = AutovecScanner()
    }

    private val structuralTargets = setOf(
        '{'.code.toByte(), '}'.code.toByte(),
        '['.code.toByte(), ']'.code.toByte(),
        ':'.code.toByte(), ','.code.toByte(),
    )

    override fun scanStructural(data: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        val chunkSize = 16

        for (chunkStart in data.indices step chunkSize) {
            val chunkEnd = (chunkStart + chunkSize).coerceAtMost(data.size)
            for (j in chunkStart until chunkEnd) {
                if (data[j] in structuralTargets) {
                    positions.add(j)
                }
            }
        }
        return positions
    }

    override fun scanQuotes(data: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        val quote = '"'.code.toByte()
        val chunkSize = 32

        for (chunkStart in data.indices step chunkSize) {
            val chunkEnd = (chunkStart + chunkSize).coerceAtMost(data.size)
            for (j in chunkStart until chunkEnd) {
                if (data[j] == quote) {
                    positions.add(j)
                }
            }
        }
        return positions
    }

    override fun scanBytes(data: ByteArray, targets: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        val chunkSize = 16

        for (chunkStart in data.indices step chunkSize) {
            val chunkEnd = (chunkStart + chunkSize).coerceAtMost(data.size)
            for (j in chunkStart until chunkEnd) {
                if (data[j] in targets) {
                    positions.add(j)
                }
            }
        }
        return positions
    }

    override fun capabilities(): ScannerCapabilities = ScannerCapabilities(
        name = "Autovec",
        vectorBits = 128,
        estimatedThroughputGbps = 1.0,
        ebpfJitEnabled = false,
        kernelAcceleration = KernelAcceleration.None,
        simdInstructionSets = emptyList(),
    )

    override fun ebpfJitTarget(): EbpfJitTarget? = null

    override fun executeVectorOp(
        kernel: SimdKernel,
        target: EbpfJitTarget,
        data: ByteArray,
    ): List<Int> = emptyList()
}

/** Create optimal SIMD scanner for current platform */
fun createOptimalScanner(): SimdScanner {
    // On JVM, use AutovecScanner as the best available option
    // Native SIMD intrinsics would require Kotlin/Native with platform-specific code
    return AutovecScanner()
}

/** Protocol detection using SIMD scanning */
class ProtocolDetector {
    private val scanner: SimdScanner = createOptimalScanner()

    companion object {
        fun new(): ProtocolDetector = ProtocolDetector()
    }

    /** Detect protocol from data using SIMD acceleration */
    fun detectProtocol(data: ByteArray): ProtocolDetection {
        if (data.isEmpty()) return ProtocolDetection.Unknown

        // Check for SOCKS5 first (most efficient)
        if (data.size >= 2 && data[0] == 0x05.toByte()) {
            return ProtocolDetection.Socks5
        }

        // HTTP method detection
        detectHttpMethod(data)?.let { method ->
            return ProtocolDetection.Http(method)
        }

        // JSON detection using structural scanning
        val structural = scanner.scanStructural(data)
        if (structural.isNotEmpty() && data.firstOrNull() == '{'.code.toByte()) {
            return ProtocolDetection.Json
        }

        return ProtocolDetection.Unknown
    }

    private fun detectHttpMethod(data: ByteArray): HttpMethod? {
        val spaces = scanForByte(data, ' '.code.toByte())
        if (spaces.isNotEmpty()) {
            val firstSpace = spaces.first()
            if (firstSpace < data.size) {
                val methodBytes = data.copyOf(firstSpace)
                return HttpMethod.fromBytes(methodBytes)
            }
        }
        return null
    }

    private fun scanForByte(data: ByteArray, target: Byte): List<Int> {
        val positions = mutableListOf<Int>()
        for ((i, b) in data.withIndex()) {
            if (b == target) positions.add(i)
        }
        return positions
    }
}

enum class ProtocolDetection {
    Http,
    Socks5,
    Tls,
    Dns,
    WebSocket,
    Json,
    Unknown,
}

enum class HttpMethod {
    Get, Post, Put, Delete, Head, Options, Connect, Patch, Trace;

    companion object {
        fun fromBytes(bytes: ByteArray): HttpMethod? = when {
            bytes.contentEquals("GET".toByteArray(Charsets.UTF_8)) -> Get
            bytes.contentEquals("POST".toByteArray(Charsets.UTF_8)) -> Post
            bytes.contentEquals("PUT".toByteArray(Charsets.UTF_8)) -> Put
            bytes.contentEquals("DELETE".toByteArray(Charsets.UTF_8)) -> Delete
            bytes.contentEquals("HEAD".toByteArray(Charsets.UTF_8)) -> Head
            bytes.contentEquals("OPTIONS".toByteArray(Charsets.UTF_8)) -> Options
            bytes.contentEquals("CONNECT".toByteArray(Charsets.UTF_8)) -> Connect
            bytes.contentEquals("PATCH".toByteArray(Charsets.UTF_8)) -> Patch
            bytes.contentEquals("TRACE".toByteArray(Charsets.UTF_8)) -> Trace
            else -> null
        }
    }

    fun asBytes(): ByteArray = when (this) {
        Get -> "GET".toByteArray(Charsets.UTF_8)
        Post -> "POST".toByteArray(Charsets.UTF_8)
        Put -> "PUT".toByteArray(Charsets.UTF_8)
        Delete -> "DELETE".toByteArray(Charsets.UTF_8)
        Head -> "HEAD".toByteArray(Charsets.UTF_8)
        Options -> "OPTIONS".toByteArray(Charsets.UTF_8)
        Connect -> "CONNECT".toByteArray(Charsets.UTF_8)
        Patch -> "PATCH".toByteArray(Charsets.UTF_8)
        Trace -> "TRACE".toByteArray(Charsets.UTF_8)
    }
}
