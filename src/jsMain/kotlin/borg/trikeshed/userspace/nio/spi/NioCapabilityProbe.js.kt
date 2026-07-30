package borg.trikeshed.userspace.nio.spi

actual fun currentNioCapabilityReport(): NioCapabilityReport = NioCapabilityReport(
    backendName = "js_fetch",
    ioUringAvailable = false,
    capabilities = listOf("net", "read", "write"),
    kernelHint = "",
    checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
)
