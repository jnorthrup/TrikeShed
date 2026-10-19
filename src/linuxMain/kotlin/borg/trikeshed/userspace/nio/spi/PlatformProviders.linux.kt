package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.Synchronization
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxFileOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.Tls13CodecBackend
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(reactorSynchronization: Synchronization): List<CoroutineContext.Element> {
    val report = currentNioCapabilityReport()

    val channelOperations = PosixChannelOperations()
    val fileOperations = LinuxFileOperations()
    val tlsBackend = Tls13CodecBackend(fileOperations)
    return listOf(
        report,
        fileOperations,
        LinuxSystemOperations(),
        channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
        PosixReactorOperations(reactorSynchronization),
        PosixProcessOperations(),
    )
}
