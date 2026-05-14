package borg.trikeshed.htx.client

import borg.trikeshed.userspace.reactor.UringReactor

private val reactor = UringReactor()

actual fun createHttpsHandler(): HtxRequestHandler = ringHttpsHandler(reactor)
