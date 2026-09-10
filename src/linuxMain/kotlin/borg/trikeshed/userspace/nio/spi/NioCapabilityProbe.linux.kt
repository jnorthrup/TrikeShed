package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.openUserspaceChannelBackend

actual fun currentNioCapabilityReport(): NioCapabilityReport {
    val backend = openUserspaceChannelBackend(2)
    return try {
        NioCapabilityReport(
            backendName = if (backend.nativeCapabilities != 0L) "io_uring" else "uring-emulated-posix",
            ioUringAvailable = backend.nativeCapabilities != 0L,
            capabilities = UringOp.entries.filter { backend.capabilities and it.mask != 0L }.map { it.name.lowercase() },
            kernelHint = backend.availability,
            checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
    } finally { backend.close() }
}
