package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.JsChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JsProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JsReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JsFileOperations
import borg.trikeshed.userspace.nio.file.spi.JsSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.StubTlsCodecBackend
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val channelOperations = JsChannelOperations()
    val tlsBackend = StubTlsCodecBackend()
    return listOf(
    currentNioCapabilityReport(),
    JsFileOperations(),
    JsSystemOperations(),
    channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
    JsReactorOperations(),
    JsProcessOperations(),
)


}
