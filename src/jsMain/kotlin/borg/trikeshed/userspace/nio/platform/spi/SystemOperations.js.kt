package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.file.spi.JsSystemOperations

actual fun loadDefaultSystemOperations(): SystemOperations = JsSystemOperations()
