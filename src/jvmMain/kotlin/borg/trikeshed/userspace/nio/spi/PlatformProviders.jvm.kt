package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.file.fileOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val fs = JvmFileOperations()
    fileOperations = fs
    val channels = JvmChannelOperations()
    val reactor = JvmReactorOperations(channels)
    return listOf(
        fs,
        JvmSystemOperations(),
        channels,
        reactor,
        JvmProcessOperations(),
    )
}
