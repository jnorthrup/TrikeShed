package borg.trikeshed.htx.client

import borg.trikeshed.userspace.reactor.UringReactor

private val reactor = UringReactor(
    channelOps = TODO(),
    reactorOps = TODO(),
    parentJob = TODO()
)

actual fun createHttpsHandler(): HtxRequestHandler = ringHttpsHandler(reactor)
