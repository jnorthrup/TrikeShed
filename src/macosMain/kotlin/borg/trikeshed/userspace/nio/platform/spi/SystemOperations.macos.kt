package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.file.spi.PosixSystemOperations

actual fun loadDefaultSystemOperations(): SystemOperations = PosixSystemOperations()
