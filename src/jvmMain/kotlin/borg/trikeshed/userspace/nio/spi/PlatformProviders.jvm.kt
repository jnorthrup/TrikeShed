package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.JvmTlsCodecBackend
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val channelOperations = JvmChannelOperations()
    val reactorOperations = JvmReactorOperations()
    val tlsBackend = JvmTlsCodecBackend()

    val report = NioCapabilityReport(
        backendName = "jvm_nio",
        ioUringAvailable = false,
        capabilities = listOf("read", "write", "fsync", "poll", "net"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    )

    return listOf(
        report,
        JvmFileOperations(),
        JvmSystemOperations(),
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
