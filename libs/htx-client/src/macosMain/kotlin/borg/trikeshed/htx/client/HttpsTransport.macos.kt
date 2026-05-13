package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations

private val platformChannels = PosixChannelOperations()
private val platformReactor = PosixReactorOperations(platformChannels)

actual fun createHttpsHandler(): HtxRequestHandler =
    ringHttpsHandler(platformChannels, platformReactor)
