package borg.trikeshed.userspace.nio.platform.spi

actual fun platformFlock(fd: Int, operation: Int): Int = TODO("flock(2) has no binding on this runtime")
