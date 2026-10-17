package borg.trikeshed.userspace.nio.platform.spi

actual fun platformGeteuid(): UInt = TODO("geteuid(2) has no binding on this runtime")
