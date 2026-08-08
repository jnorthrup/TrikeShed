package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.userspace.nio.file.spi.PosixSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.TlsCodecBackend
import borg.trikeshed.reactor.TlsCodecResult
import borg.trikeshed.reactor.TlsConfig
import borg.trikeshed.reactor.TlsFlowState
import borg.trikeshed.reactor.TlsPayload
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
