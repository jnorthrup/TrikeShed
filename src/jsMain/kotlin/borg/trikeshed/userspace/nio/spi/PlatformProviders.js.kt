package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.JsChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JsProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JsReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JsFileOperations
import borg.trikeshed.userspace.nio.file.spi.JsSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.TlsCodecBackend
import borg.trikeshed.reactor.TlsCodecResult
import borg.trikeshed.reactor.TlsConfig
import borg.trikeshed.reactor.TlsFlowState
import borg.trikeshed.reactor.TlsPayload
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val channelOperations = JsChannelOperations()
    val tlsBackend = StubTlsCodecBackend()
    return listOf(
    NioCapabilityReport(
        backendName = "js_fetch",
        ioUringAvailable = false,
        capabilities = listOf("net", "read", "write"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ),
    JsFileOperations(),
    JsSystemOperations(),
    channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
    JsReactorOperations(),
    JsProcessOperations(),
)


}

private class StubTlsCodecBackend : TlsCodecBackend {
    override suspend fun handshake(config: TlsConfig, state: TlsFlowState): TlsCodecResult =
        throw UnsupportedOperationException("TLS not yet implemented for this platform")
    override suspend fun upstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult =
        throw UnsupportedOperationException("TLS not yet implemented for this platform")
    override suspend fun downstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult =
        throw UnsupportedOperationException("TLS not yet implemented for this platform")
    override suspend fun close(config: TlsConfig, state: TlsFlowState): TlsCodecResult =
        throw UnsupportedOperationException("TLS not yet implemented for this platform")
}
