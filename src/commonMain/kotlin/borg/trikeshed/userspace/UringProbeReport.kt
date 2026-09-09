package borg.trikeshed.userspace

import borg.trikeshed.platform.HostDescriptor
import kotlinx.serialization.Serializable

@Serializable
enum class UringProbeState {
    HOST_UNSUPPORTED, HOST_UNKNOWN, RUNTIME_RESTRICTED, MODULE_ABSENT,
    ARCH_UNSUPPORTED, ARCH_MISMATCH, ABI_MISMATCH, MODULE_LOAD_FAILED,
    SETUP_DENIED, KERNEL_UNSUPPORTED, PROBE_FAILED, OPERATIONS_UNSUPPORTED,
    AVAILABLE_LIMITED, AVAILABLE,
}

@Serializable
enum class UringProbePhase { DISCOVERY, LOAD, SETUP, OPERATIONS, EXECUTION }

@Serializable
data class UringProbeReport(
    val host: HostDescriptor,
    val state: UringProbeState,
    val phase: UringProbePhase = UringProbePhase.DISCOVERY,
    val module: String? = null,
    val detail: String,
    val errno: Int? = null,
    /** Null means not established; zero means a callable probe found no supported operations. */
    val nativeCapabilities: Long? = null,
) {
    val available: Boolean get() = state == UringProbeState.AVAILABLE || state == UringProbeState.AVAILABLE_LIMITED
    val description: String get() = "$state: $detail" + (errno?.let { " (errno=$it)" } ?: "")
}

/** An opened candidate transfers its resources to the canonical selector. */
data class UringBackendDiscovery(val report: UringProbeReport, val backend: UserspaceChannelBackend? = null)

internal fun uringErrnoState(errno: Int): UringProbeState = when (errno) {
    1, 13 -> UringProbeState.SETUP_DENIED
    38, 95 -> UringProbeState.KERNEL_UNSUPPORTED
    else -> UringProbeState.PROBE_FAILED
}

internal fun uringOperationState(native: Long, implemented: Long): UringProbeState = when {
    native == 0L -> UringProbeState.OPERATIONS_UNSUPPORTED
    native and implemented.inv() != 0L -> UringProbeState.PROBE_FAILED
    native == implemented -> UringProbeState.AVAILABLE
    else -> UringProbeState.AVAILABLE_LIMITED
}
