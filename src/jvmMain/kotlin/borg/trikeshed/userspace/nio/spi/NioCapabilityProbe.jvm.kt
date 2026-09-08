package borg.trikeshed.userspace.nio.spi

actual fun currentNioCapabilityReport(): NioCapabilityReport =
    uringCapabilityReport(java.lang.System.currentTimeMillis())
