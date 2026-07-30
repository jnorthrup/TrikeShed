package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.PosixUringIO

actual fun currentNioCapabilityReport(): NioCapabilityReport {
    val uringAvailable = PosixUringIO.isAvailable(entries = 2)
    val kernelHint = runCatching {
        listOf("/proc/modules", "/proc/sys/kernel/io_uring_disabled").mapNotNull { path ->
            runCatching { java.io.File(path).readText().trim().take(80) }.getOrNull()
        }.firstOrNull { it.contains("io_uring", ignoreCase = true) } ?: ""
    }.getOrDefault("")

    return NioCapabilityReport(
        backendName = if (uringAvailable) "io_uring" else "posix_aio",
        ioUringAvailable = uringAvailable,
        capabilities = if (uringAvailable) listOf("read", "write", "fsync", "poll", "net") else listOf("read", "write", "fsync"),
        kernelHint = kernelHint,
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    )
}
