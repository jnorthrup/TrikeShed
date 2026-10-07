package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.WasmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.WasmFileOperations
import borg.trikeshed.userspace.nio.file.spi.WasmSystemOperations
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.reactor.StubTlsCodecBackend
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val channelOperations = WasmChannelOperations()
    val tlsBackend = StubTlsCodecBackend()
    return listOf(
    NioCapabilityReport(
        backendName = "wasm_js_fetch",
        ioUringAvailable = false,
        capabilities = listOf("net", "read", "write"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ),
    WasmFileOperations(),
    WasmSystemOperations(),
    channelOperations,
        tlsBackend,
        HtxReactorElement(channelOperations = channelOperations, tlsBackend = tlsBackend),
    WasmReactorOperations(),
    WasmProcessOperations(),
)


}
