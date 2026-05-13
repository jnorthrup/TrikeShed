package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.fileOperations
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.userspace.nio.file.spi.PosixSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val fileOps = PosixFileOperations()
    fileOperations = fileOps
    val channels = PosixChannelOperations()
    val reactor = PosixReactorOperations(channels)
    return listOf(
        fileOps,
        PosixSystemOperations(),
        channels,
        reactor,
        PosixProcessOperations(),
    )
}
