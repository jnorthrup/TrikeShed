package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.file.spi.WasmSystemOperations

actual fun loadDefaultSystemOperations(): SystemOperations = WasmSystemOperations()
