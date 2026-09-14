package borg.trikeshed.userspace.nio.file.spi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext

internal actual val fileIoContext: CoroutineContext get() = Dispatchers.IO
