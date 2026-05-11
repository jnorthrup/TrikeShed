package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.lib.fileOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val fs = JvmFileOperations()
    fileOperations = fs
    return listOf(
        fs,
        JvmSystemOperations(),
        JvmChannelOperations(),
        JvmReactorOperations(),
        JvmProcessOperations(),
    )
}
