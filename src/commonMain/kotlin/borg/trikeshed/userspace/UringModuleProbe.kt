package borg.trikeshed.userspace

import borg.trikeshed.platform.HostDescriptor
import borg.trikeshed.platform.normalizeHostArchitecture

/** ELF header validation precedes loading the repository's JNI module. */
internal fun uringElfCompatibility(header: ByteArray, architecture: String?): UringProbeState? {
    if (header.size < 20 || header[0] != 0x7f.toByte() || header[1] != 69.toByte() ||
        header[2] != 76.toByte() || header[3] != 70.toByte()) return UringProbeState.ABI_MISMATCH
    if (header[4] != 2.toByte() || header[5] != 1.toByte() || header[6] != 1.toByte())
        return UringProbeState.ABI_MISMATCH
    val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
    val expected = when (normalizeHostArchitecture(architecture)) {
        "x86_64" -> 62
        "arm64" -> 183
        else -> return UringProbeState.ARCH_UNSUPPORTED
    }
    return if (machine == expected) null else UringProbeState.ARCH_MISMATCH
}

internal fun uringHostConstraint(host: HostDescriptor): UringProbeState? = when {
    host.os == null -> UringProbeState.HOST_UNKNOWN
    host.os != "linux" -> UringProbeState.HOST_UNSUPPORTED
    host.architecture == null -> UringProbeState.HOST_UNKNOWN
    host.architecture !in arrayOf("arm64", "x86_64") -> UringProbeState.ARCH_UNSUPPORTED
    else -> null
}

/** Validate the executable candidate, including a completed kernel NOP. */
internal fun probeUringBackend(host: HostDescriptor, module: String, backend: UserspaceChannelBackend): UringBackendDiscovery {
    var native: Long? = null
    var phase = UringProbePhase.OPERATIONS
    fun probe(): UringBackendDiscovery {
        return try {
            val mask = backend.nativeCapabilities
            native = mask
            val implemented = backend.capabilities
            val required = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
                UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)
            val state = uringOperationState(mask, implemented)
            if (implemented and required != required || state == UringProbeState.PROBE_FAILED)
                return UringBackendDiscovery(UringProbeReport(host, UringProbeState.PROBE_FAILED, phase,
                    module, "Module does not implement the shared file operation contract", nativeCapabilities = native))
            if (state == UringProbeState.OPERATIONS_UNSUPPORTED || mask and UringOp.NOP.mask == 0L)
                return UringBackendDiscovery(UringProbeReport(host, UringProbeState.OPERATIONS_UNSUPPORTED, phase,
                    module, "No kernel NOP support for the execution probe", nativeCapabilities = native))
            phase = UringProbePhase.EXECUTION
            val token = 0x7472696b65L
            val completed = backend.submitBatch(listOf(UringOp.Companion.UringSubmission(
                UringOp.NOP, -1, 0, 0, 0, userData = token)))
            if (completed.size != 1 || completed[0].userData != token)
                return UringBackendDiscovery(UringProbeReport(host, UringProbeState.PROBE_FAILED, phase,
                    module, "NOP completion count or token mismatch", nativeCapabilities = native))
            val result = completed[0].res
            if (result != 0) return UringBackendDiscovery(UringProbeReport(host,
                if (result < 0) uringErrnoState(-result) else UringProbeState.PROBE_FAILED,
                phase, module, "Kernel NOP execution failed", if (result < 0) -result else null, native))
            UringBackendDiscovery(UringProbeReport(host, state, phase, module,
                "Setup, operation probe and kernel NOP completed; unsupported operations retain emulated uring semantics",
                nativeCapabilities = native), backend)
        } catch (failure: Exception) {
            UringBackendDiscovery(UringProbeReport(host, UringProbeState.PROBE_FAILED, phase,
                module, failure.message ?: failure.toString(), nativeCapabilities = native))
        }
    }
    val result = try {
        probe()
    } catch (failure: Throwable) {
        // Linkage errors must reach the host loader without leaking an opened ring.
        try { backend.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
    if (result.backend != null) return result
    return try {
        backend.close()
        result
    } catch (failure: Exception) {
        result.copy(report = result.report.copy(detail = result.report.detail +
            "; module cleanup failed: " + (failure.message ?: failure.toString())))
    }
}
