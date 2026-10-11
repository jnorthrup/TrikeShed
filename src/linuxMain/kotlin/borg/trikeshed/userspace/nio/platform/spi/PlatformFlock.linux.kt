package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.posixCompletion
import platform.linux.flock

actual fun platformFlock(fd: Int, operation: Int): Int = posixCompletion(flock(fd, operation))
