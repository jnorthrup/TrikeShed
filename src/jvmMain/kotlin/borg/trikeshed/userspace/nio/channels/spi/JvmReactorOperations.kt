package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
/**
 * JVM ReactorOperations — poll registered fds for read-readiness via JvmChannelOperations.
 *
 * CCEK-compliant: AsyncContextElement with CREATED → OPEN → DRAINING → CLOSED lifecycle.
 * No JDK imports. Readiness detection delegates to JvmChannelOperations.hasPending().
 */
class JvmReactorOperations(
    private val channels: JvmChannelOperations,
    parentJob: Job? = null,
) : AsyncContextElement(parentJob = parentJob), ReactorOperations {

    companion object Key : AsyncContextKey<JvmReactorOperations>()
    override val key get() = Key

    private data class Registration(val interests: Set<Interest>, val userData: Long)

    private val registered = mutableMapOf<Int, Registration>()

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state == ElementState.ACTIVE) {
            state = ElementState.DRAINING
            registered.clear()
            super.close()
        }
    }

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
                if (Interest.READ in reg.interests && channels.hasPending(fd)) ReactorSignal(
                    fd,
                    setOf(Interest.READ),
                    reg.userData,
                )
                else null
            }
            if (ready.isNotEmpty()) return ready
            if (System.currentTimeMillis() >= deadline) return emptyList()
            delay(1L)
        }
    }
}