package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.file.fileOperations
import borg.trikeshed.userspace.nio.channels.spi.JsChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JsProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.JsReactorOperations
import borg.trikeshed.userspace.nio.file.spi.JsFileOperations
import borg.trikeshed.userspace.nio.file.spi.JsSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val fs = JsFileOperations()
    fileOperations = fs
    return listOf(
        fs,
        JsSystemOperations(),
        JsChannelOperations(),
        JsReactorOperations(),
        JsProcessOperations(),
    )
}
