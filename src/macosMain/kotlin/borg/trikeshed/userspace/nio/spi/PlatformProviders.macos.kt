package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.userspace.nio.file.spi.PosixSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> = listOf(
    NioCapabilityReport(
        backendName = "kqueue",
        ioUringAvailable = false,
        capabilities = listOf("read", "write", "fsync", "poll", "net"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ),
    PosixFileOperations(),
    PosixSystemOperations(),
    PosixChannelOperations(),
    PosixReactorOperations(),
    PosixProcessOperations(),
)
