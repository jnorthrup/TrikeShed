package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.reactor.UringReactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createHttpsHandler(): HtxRequestHandler {
    val channelOps = JvmChannelOperations()
    val reactor = UringReactor(channelOps)
    val handler = ringHttpsHandler(reactor)
    return { request: HtxClientRequest ->
        withContext(Dispatchers.IO) { handler(request) }
    }
}
