package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.PosixUringIO
import borg.trikeshed.userspace.nio.channels.spi.LinuxChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxFileOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.TlsCodecBackend
import borg.trikeshed.reactor.TlsCodecResult
import borg.trikeshed.reactor.TlsConfig
import borg.trikeshed.reactor.TlsFlowState
import borg.trikeshed.reactor.TlsPayload
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val uringAvailable = PosixUringIO.isAvailable(entries = 2)
    val kernelHint = runCatching {
        val modules = listOf("/proc/modules", "/proc/sys/kernel/io_uring_disabled").mapNotNull { path ->
            runCatching { java.io.File(path).readText().trim().take(80) }.getOrNull()
        }
        modules.firstOrNull { it.contains("io_uring", ignoreCase = true) } ?: ""
    }.getOrDefault("")

    val report = NioCapabilityReport(
        backendName = if (uringAvailable) "io_uring" else "posix_aio",
        ioUringAvailable = uringAvailable,
        capabilities = if (uringAvailable) listOf("read", "write", "fsync", "poll", "net") else listOf("read", "write", "fsync"),
        kernelHint = kernelHint,
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    )

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
