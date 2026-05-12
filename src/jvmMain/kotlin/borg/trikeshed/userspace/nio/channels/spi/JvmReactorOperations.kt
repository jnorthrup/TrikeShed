package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * JVM ReactorOperations — poll registered fds for read-readiness by asking
 * the uring emulation layer (UserspaceChannelBackend) via JvmChannelOperations.
 *
 * No JDK imports. Readiness detection delegates to JvmChannelOperations.hasPending()
 * which routes through the uring emulation in UserspaceIO.jvm.kt.
 */
class JvmReactorOperations(
    private val channels: JvmChannelOperations,
) : ReactorOperations {
    override val key get() = ReactorOperations.Key

    private data class Registration(val interests: Set<Interest>, val userData: Long)

    private val registered = mutableMapOf<Int, Registration>()

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        registered[fd] = Registration(interests, userData)
    }

    override fun deregister(fd: Int) {
        registered.remove(fd)
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        val deadline = if (timeout.isInfinite()) Long.MAX_VALUE
                       else System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (true) {
            val ready = registered.entries.mapNotNull { (fd, reg) ->
                if (Interest.READ in reg.interests && channels.hasPending(fd))
                    ReactorSignal(fd, setOf(Interest.READ), reg.userData)
                else null
            }
            if (ready.isNotEmpty()) return ready
            if (System.currentTimeMillis() >= deadline) return emptyList()
            delay(1L)
        }
    }
}
