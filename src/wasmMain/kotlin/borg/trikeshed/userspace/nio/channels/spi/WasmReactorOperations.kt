package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlin.time.Duration

class WasmReactorOperations : ReactorOperations {

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {}
    override fun deregister(fd: Int) {}
    override suspend fun poll(timeout: Duration): List<ReactorSignal> = emptyList()
}
