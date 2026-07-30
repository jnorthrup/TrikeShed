package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.channels.spi.JsChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JsProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JsReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JsFileOperations
import borg.trikeshed.userspace.nio.file.spi.JsSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> = listOf(
    NioCapabilityReport(
        backendName = "js_fetch",
        ioUringAvailable = false,
        capabilities = listOf("net", "read", "write"),
        kernelHint = "",
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ),
    JsFileOperations(),
    JsSystemOperations(),
    JsChannelOperations(),
    JsReactorOperations(),
    JsProcessOperations(),
)
