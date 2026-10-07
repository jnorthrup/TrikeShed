package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.LinuxChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxFileOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.StubTlsCodecBackend
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val report = currentNioCapabilityReport()

    val channelOperations = LinuxChannelOperations()
    val tlsBackend = StubTlsCodecBackend()
    return listOf(
        report,
        LinuxFileOperations(),
        LinuxSystemOperations(),
        channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
        PosixReactorOperations(),
        PosixProcessOperations(),
    )
}
