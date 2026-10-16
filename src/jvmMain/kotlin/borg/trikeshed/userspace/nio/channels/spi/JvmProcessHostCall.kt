package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Blocking host/protocol boundary only; common ProcessCalls owns scheduling, deadlines and drain. */
suspend fun <T> processHostCall(operation: () -> T): T = runInterruptible(Dispatchers.IO, operation)
