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

    return listOf(
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
