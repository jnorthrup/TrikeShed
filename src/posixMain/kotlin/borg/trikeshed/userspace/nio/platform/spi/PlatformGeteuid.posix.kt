package borg.trikeshed.userspace.nio.platform.spi

import platform.posix.geteuid

actual fun platformGeteuid(): UInt = geteuid()
