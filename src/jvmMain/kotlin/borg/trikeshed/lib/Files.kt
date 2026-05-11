@file:OptIn(ExperimentalTime::class)

package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlin.time.ExperimentalTime

internal actual fun defaultFileOperations(): FileOperations = JvmFileOperations()
