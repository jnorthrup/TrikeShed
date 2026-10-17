package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.StubTlsCodecBackend
import borg.trikeshed.userspace.nio.channels.spi.WasiChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.WasiFileOperations
import borg.trikeshed.userspace.nio.platform.spi.WasiSystemOperations
import borg.trikeshed.userspace.wasi.Wasi
import kotlin.coroutines.CoroutineContext

actual fun currentNioCapabilityReport(): NioCapabilityReport = NioCapabilityReport(
    backendName = "uring_emulated_wasi",
    ioUringAvailable = false,
    capabilities = listOf("read", "write", "fsync"),
    kernelHint = "",
    checkedAt = Wasi.realtimeNanos() / 1_000_000L,
)

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val channelOperations = WasiChannelOperations()
    val tlsBackend = StubTlsCodecBackend()
    return listOf(
        currentNioCapabilityReport(),
        WasiFileOperations(),
        WasiSystemOperations(),
        channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
        WasmReactorOperations(),
        WasmProcessOperations(),
    )
}
