package borg.trikeshed.forge.platform

import kotlinx.coroutines.Dispatchers
import java.util.UUID

actual object PlatformUtils {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    actual fun randomUuid(): String = UUID.randomUUID().toString()

    actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
}