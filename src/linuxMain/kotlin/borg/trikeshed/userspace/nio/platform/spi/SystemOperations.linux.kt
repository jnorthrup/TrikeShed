package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.file.spi.LinuxSystemOperations

actual fun loadDefaultSystemOperations(): SystemOperations = LinuxSystemOperations()
