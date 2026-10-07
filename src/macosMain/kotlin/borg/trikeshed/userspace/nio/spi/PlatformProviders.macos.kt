package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.userspace.nio.file.spi.PosixSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.StubTlsCodecBackend
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val channelOperations = PosixChannelOperations()
    val tlsBackend = StubTlsCodecBackend()
    return listOf(
    NioCapabilityReport(
        backendName = "kqueue",
        ioUringAvailable = false,
        capabilities = listOf("read", "write", "fsync", "poll", "net"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ),
    PosixFileOperations(),
    PosixSystemOperations(),
    channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
    PosixReactorOperations(),
    PosixProcessOperations(),
)


}
