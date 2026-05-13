package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createHttpsHandler(): HtxRequestHandler {
    val channels = JvmChannelOperations()
    val reactor = JvmReactorOperations(channels)
    val ringHandler = ringHttpsHandler(channels, reactor)
    return { request: HtxClientRequest ->
        withContext(Dispatchers.IO) {
            ringHandler(request)
        }
    }
}
