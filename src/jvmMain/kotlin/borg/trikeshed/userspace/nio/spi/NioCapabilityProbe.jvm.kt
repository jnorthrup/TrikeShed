package borg.trikeshed.userspace.nio.spi

actual fun currentNioCapabilityReport(): NioCapabilityReport = NioCapabilityReport(
    backendName = "jvm_nio",
    ioUringAvailable = false,
    capabilities = listOf("read", "write", "fsync", "poll", "net"),
    kernelHint = "",
    checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
)
