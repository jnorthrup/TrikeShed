package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.lib.fileOperations
import borg.trikeshed.userspace.nio.channels.spi.LinuxChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxFileOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val fs = LinuxFileOperations()
    fileOperations = fs
    return listOf(
        fs,
        LinuxSystemOperations(),
        LinuxChannelOperations(),
        PosixReactorOperations(),
        PosixProcessOperations(),
    )
}
