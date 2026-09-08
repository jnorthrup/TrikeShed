package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.openUserspaceChannelBackend

/** Snapshot the canonical selector, releasing the temporary backend after observation. */
internal fun uringCapabilityReport(checkedAt: Long): NioCapabilityReport {
    val backend = openUserspaceChannelBackend(2)
    try {
        val native = backend.nativeCapabilities != 0L
        return NioCapabilityReport(
            backendName = if (native) "io_uring" else "uring_emulated",
            ioUringAvailable = native,
            capabilities = UringOp.entries.filter { backend.capabilities and it.mask != 0L }.map { it.name.lowercase() },
            kernelHint = backend.probeReport?.host?.takeIf { it.os == "linux" }?.osVersion.orEmpty(),
            checkedAt = checkedAt,
            uringProbe = backend.probeReport,
        )
    } finally {
        backend.close()
    }
}
