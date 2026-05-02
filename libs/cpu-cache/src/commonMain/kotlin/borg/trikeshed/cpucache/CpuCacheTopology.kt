package borg.trikeshed.cpucache

/**
 * Full cache topology for a single CPU core.
 *
 * All sizes in bytes. Null means unavailable/unknown on this platform.
 *
 * Platform Coverage Matrix:
 * - JVM (Linux): Full topology (/sys)
 * - JVM (macOS): Full topology (sysctl)
 * - Linux Native: Cache sizes and core count (sysconf), cache line null
 * - macOS Native: Full topology (sysctlbyname)
 * - Windows Native: Null fallback (requires cinterop)
 * - JS/WASM: Null fallback
 */
data class CpuCacheTopology(
    val l1DataBytes: Long?,
    val l1InstructionBytes: Long?,
    val l2Bytes: Long?,
    val l3Bytes: Long?,
    val cacheLineBytes: Int?,
    val coreCount: Int?,
)

/**
 * Platform-specific cache interrogation.
 *
 * Each platform source set provides an [actual] implementation.
 */
expect fun interrogateCpuCache(): CpuCacheTopology
