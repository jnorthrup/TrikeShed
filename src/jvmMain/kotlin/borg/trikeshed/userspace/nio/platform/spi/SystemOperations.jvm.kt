package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.file.spi.JvmSystemOperations

actual fun loadDefaultSystemOperations(): SystemOperations = JvmSystemOperations()
