package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.LinuxChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxFileOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> = listOf(
    LinuxFileOperations(),
    LinuxSystemOperations(),
    LinuxChannelOperations(),
    PosixReactorOperations(),
    PosixProcessOperations(),
)
