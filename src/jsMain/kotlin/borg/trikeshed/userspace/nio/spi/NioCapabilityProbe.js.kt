package borg.trikeshed.userspace.nio.spi

actual fun currentNioCapabilityReport(): NioCapabilityReport =
    uringCapabilityReport(kotlin.js.Date.now().toLong())
