package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.Synchronization
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.Tls13CodecBackend
import borg.trikeshed.userspace.nio.channels.spi.MingwProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.WinsockChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.WsaPollReactorOperations
import borg.trikeshed.userspace.nio.file.spi.MingwFileOperations
import borg.trikeshed.userspace.nio.platform.spi.MingwSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun currentNioCapabilityReport(): NioCapabilityReport = NioCapabilityReport(
    backendName = "wsapoll",
    ioUringAvailable = false,
    capabilities = listOf("read", "write", "fsync", "poll", "net"),
    kernelHint = "",
    checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
)

/** Windows: the emulated ring over Winsock and the C runtime, with WSAPoll as the reactor. */
actual fun platformNioProviders(reactorSynchronization: Synchronization): List<CoroutineContext.Element> {
    val channelOperations = WinsockChannelOperations()
    val fileOperations = MingwFileOperations()
    val tlsBackend = Tls13CodecBackend(fileOperations)
    return listOf(
        currentNioCapabilityReport(),
        fileOperations,
        MingwSystemOperations(),
        channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
        WsaPollReactorOperations(reactorSynchronization),
        MingwProcessOperations(),
    )
}

/** No matrix or encoder backend on this target. */
actual fun platformMatrixProviders(): List<CoroutineContext.Element> = emptyList()
