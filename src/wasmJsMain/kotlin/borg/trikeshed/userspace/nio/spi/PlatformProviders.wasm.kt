package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.file.fileOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.WasmReactorOperations
import borg.trikeshed.userspace.nio.file.spi.WasmFileOperations
import borg.trikeshed.userspace.nio.file.spi.WasmSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val fs = WasmFileOperations()
    fileOperations = fs
    return listOf(
        fs,
        WasmSystemOperations(),
        WasmChannelOperations(),
        WasmReactorOperations(),
        WasmProcessOperations(),
    )
}
