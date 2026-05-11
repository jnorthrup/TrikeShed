@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations

internal actual fun defaultFileOperations(): FileOperations = PosixFileOperations()
