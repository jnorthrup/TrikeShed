package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.Synchronization
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.JvmTlsCodecBackend
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmSystemOperations
import borg.trikeshed.userspace.nio.platform.spi.JvmMatrixOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(reactorSynchronization: Synchronization): List<CoroutineContext.Element> {
    val channelOperations = JvmChannelOperations()
    val reactorOperations = JvmReactorOperations(reactorSynchronization)
    val tlsBackend = JvmTlsCodecBackend()

    val report = currentNioCapabilityReport()

    return listOfNotNull(
        report,
        JvmFileOperations(),
        JvmSystemOperations(),
        JvmMatrixOperations.available(),
        channelOperations,
        reactorOperations,
        JvmProcessOperations(),
        tlsBackend,
        HtxReactorElement(
            channelOperations = channelOperations,
            tlsBackend = tlsBackend,
        ),
    )
}
