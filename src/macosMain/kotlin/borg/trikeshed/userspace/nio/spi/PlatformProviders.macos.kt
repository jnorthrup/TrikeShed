package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.Synchronization
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.userspace.nio.file.spi.PosixSystemOperations
import borg.trikeshed.userspace.nio.platform.spi.MlxEncoderOperations
import borg.trikeshed.userspace.nio.platform.spi.MlxMatrixOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.Tls13CodecBackend
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(reactorSynchronization: Synchronization): List<CoroutineContext.Element> {
    val channelOperations = PosixChannelOperations()
    val fileOperations = PosixFileOperations()
    val tlsBackend = Tls13CodecBackend(fileOperations)
    return listOf(
    NioCapabilityReport(
        backendName = "kqueue",
        ioUringAvailable = false,
        capabilities = listOf("read", "write", "fsync", "poll", "net"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ),
    fileOperations,
    PosixSystemOperations(),
    MlxMatrixOperations(),
    MlxEncoderOperations(),
    channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
    PosixReactorOperations(reactorSynchronization),
    PosixProcessOperations(),
)


}
