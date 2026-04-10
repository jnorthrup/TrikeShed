package borg.literbike.betanet

/**
 * Runtime capability probe for SIMD/MLIR/eBPF gating.
 * Ported from literbike/src/betanet/capabilities.rs.
 */

private fun envOverride(key: String): Boolean? {
    return when (System.getenv(key)?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }
}

/** Returns true if AVX2 is available (or forced by env var BETANET_FORCE_AVX2) */
fun hasAvx2(): Boolean {
    envOverride("BETANET_FORCE_AVX2")?.let { return it }
    val arch = System.getProperty("os.arch").lowercase()
    return "amd64" in arch || "x86_64" in arch
}

/** Returns true if MLIR JIT is available (or forced by env var BETANET_FORCE_MLIR) */
fun hasMlir(): Boolean {
    envOverride("BETANET_FORCE_MLIR")?.let { return it }
    return false
}

/** Returns true if eBPF offload is available (or forced by env var BETANET_FORCE_EBPF) */
fun hasEbpf(): Boolean {
    envOverride("BETANET_FORCE_EBPF")?.let { return it }
    val osName = System.getProperty("os.name").lowercase()
    return "linux" in osName // On Linux, would probe /sys or verifier
}
