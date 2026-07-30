package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.WasmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.WasmFileOperations
import borg.trikeshed.userspace.nio.file.spi.WasmSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> = listOf(
    NioCapabilityReport(
        backendName = "wasm_js_fetch",
        ioUringAvailable = false,
        capabilities = listOf("net", "read", "write"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ),
    WasmFileOperations(),
    WasmSystemOperations(),
    WasmChannelOperations(),
    WasmReactorOperations(),
    WasmProcessOperations(),
)
