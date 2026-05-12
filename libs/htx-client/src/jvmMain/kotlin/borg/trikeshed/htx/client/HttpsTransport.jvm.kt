package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations

private val platformChannels = JvmChannelOperations()
private val platformReactor = JvmReactorOperations(platformChannels)

actual fun createHttpsHandler(): HtxRequestHandler =
    ringHttpsHandler(platformChannels, platformReactor)
