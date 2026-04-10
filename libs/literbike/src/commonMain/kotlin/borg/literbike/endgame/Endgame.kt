package borg.literbike.endgame

/**
 * ENDGAME Architecture - Processing path selection and capability detection.
 * Ported from literbike/src/endgame/endgame.rs and mod.rs.
 */

/**
 * SIMD capability level.
 */
enum class SimdLevel {
    None,
    Sse2,
    Avx2,
    Avx512
}

/**
 * Runtime capabilities detection for optimal processing path selection.
 */
data class EndgameCapabilities(
    val ioUringAvailable: Boolean,
    val ebpfCapable: Boolean,
    val kernelModuleLoaded: Boolean,
    val simdLevel: SimdLevel,
    val featureGates: FeatureGates
) {
    companion object {
        @Volatile
        private var cachedCapabilities: EndgameCapabilities? = null

        /** Detect runtime capabilities and feature gates */
        fun detect(): EndgameCapabilities {
            return cachedCapabilities ?: run {
                val caps = EndgameCapabilities(
                    ioUringAvailable = detectIoUring(),
                    ebpfCapable = detectEbpf(),
                    kernelModuleLoaded = detectKernelModule(),
                    simdLevel = detectSimdCapabilities(),
                    featureGates = FeatureGates.default()
                )
                cachedCapabilities = caps
                caps
            }
        }

        private fun detectIoUring(): Boolean {
            // io_uring is Linux-only; on other platforms default false
            val osName = System.getProperty("os.name").lowercase()
            if ("linux" !in osName) return false
            // Check /proc/sys/kernel/io_uring_disabled
            return try {
                val content = java.io.File("/proc/sys/kernel/io_uring_disabled").readText().trim()
                content == "0"
            } catch (e: Exception) {
                kernelVersionSupportsIoUring()
            }
        }

        private fun detectEbpf(): Boolean {
            val osName = System.getProperty("os.name").lowercase()
            if ("linux" !in osName) return false
            return java.io.File("/sys/fs/bpf").exists() &&
                    java.io.File("/proc/sys/net/core/bpf_jit_enable").exists()
        }

        private fun detectKernelModule(): Boolean {
            val osName = System.getProperty("os.name").lowercase()
            if ("linux" !in osName) return false
            return try {
                val modules = java.io.File("/proc/modules").readText()
                "litebike" in modules
            } catch (e: Exception) {
                false
            }
        }

        private fun detectSimdCapabilities(): SimdLevel {
            val arch = System.getProperty("os.arch").lowercase()
            if ("amd64" in arch || "x86_64" in arch) {
                // On JVM, SIMD support depends on JVM flags and architecture.
                // We conservatively report Avx2 as available on x86_64 since
                // modern JVMs auto-vectorize with AVX2 when available.
                return SimdLevel.Avx2
            }
            return SimdLevel.None
        }

        private fun kernelVersionSupportsIoUring(): Boolean {
            return try {
                val version = java.io.File("/proc/version").readText()
                val versionStart = version.indexOf("Linux version ")
                if (versionStart < 0) return false
                val versionPart = version.substring(versionStart + 14)
                val versionEnd = versionPart.indexOf(' ')
                if (versionEnd < 0) return false
                val versionStr = versionPart.substring(0, versionEnd)
                val parts = versionStr.split('.')
                if (parts.size >= 2) {
                    val major = parts[0].toIntOrNull() ?: return false
                    val minor = parts[1].toIntOrNull() ?: return false
                    major > 5 || (major == 5 && minor >= 1)
                } else false
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Select optimal processing path based on capabilities and gates */
    fun selectOptimalPath(): ProcessingPath {
        if (featureGates.kernelDirect && kernelModuleLoaded) {
            return ProcessingPath.KernelDirect
        }

        if (featureGates.ebpfOffload && featureGates.ioUringNative &&
            ebpfCapable && ioUringAvailable
        ) {
            return ProcessingPath.EbpfIoUring
        }

        if (featureGates.ioUringNative && ioUringAvailable) {
            return ProcessingPath.IoUringUserspace
        }

        return ProcessingPath.TokioFallback
    }

    /** Check if bounty requirements can be met */
    fun bountyCompatible(): Boolean = true

    /** Get performance multiplier estimate for current path */
    fun performanceMultiplier(): Double = when (selectOptimalPath()) {
        ProcessingPath.KernelDirect -> 10.0
        ProcessingPath.EbpfIoUring -> 5.0
        ProcessingPath.IoUringUserspace -> 2.0
        ProcessingPath.TokioFallback -> 1.0
    }
}

/**
 * Feature gates for endgame capabilities.
 */
data class FeatureGates(
    val removeReactor: Boolean = false,
    val ioUringNative: Boolean = false,
    val ebpfOffload: Boolean = false,
    val unifiedProtocolEngine: Boolean = false,
    val kernelDirect: Boolean = false
) {
    companion object {
        fun default(): FeatureGates {
            // In Kotlin, we check environment variables for feature gate overrides
            return FeatureGates(
                removeReactor = envBool("LITERBIKE_REMOVE_REACTOR"),
                ioUringNative = envBool("LITERBIKE_IO_URING_NATIVE"),
                ebpfOffload = envBool("LITERBIKE_EBPF_OFFLOAD"),
                unifiedProtocolEngine = envBool("LITERBIKE_UNIFIED_PROTOCOL_ENGINE"),
                kernelDirect = envBool("LITERBIKE_KERNEL_DIRECT")
            )
        }

        private fun envBool(key: String): Boolean {
            return when (System.getenv(key)?.lowercase()) {
                "1", "true", "yes", "on" -> true
                else -> false
            }
        }
    }
}

/**
 * Processing path enumeration.
 */
enum class ProcessingPath {
    /** Full kernel module - everything in kernel space */
    KernelDirect,
    /** eBPF + io_uring - protocol parsing in kernel, I/O via io_uring */
    EbpfIoUring,
    /** io_uring only - userspace processing, kernel I/O */
    IoUringUserspace,
    /** Fallback - full userspace (bounty-safe default) */
    TokioFallback
}

/**
 * Processing path selector - functional equivalent of the endgame_path! macro.
 */
fun <T> endgamePath(
    kernelDirect: () -> T,
    ebpfIoUring: () -> T,
    ioUring: () -> T,
    tokioFallback: () -> T
): T {
    val caps = EndgameCapabilities.detect()
    return when (caps.selectOptimalPath()) {
        ProcessingPath.KernelDirect -> kernelDirect()
        ProcessingPath.EbpfIoUring -> ebpfIoUring()
        ProcessingPath.IoUringUserspace -> ioUring()
        ProcessingPath.TokioFallback -> tokioFallback()
    }
}
