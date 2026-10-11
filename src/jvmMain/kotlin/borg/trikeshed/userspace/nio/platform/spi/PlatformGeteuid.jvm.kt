package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.JvmFileSyscalls

actual fun platformGeteuid(): UInt = JvmFileSyscalls.geteuid()
