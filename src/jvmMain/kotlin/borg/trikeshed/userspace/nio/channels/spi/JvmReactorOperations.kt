package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.reactor.Interest
import kotlin.time.Duration

/**
 * JVM actual of the reactor SPI. Readiness is POLL_ADD / POLL_REMOVE SQEs on
 * the uring facade — the same ring, same CQE contract, one IO flavor. The
 * Selector is the backend's emulation substrate for POLL_ADD execution; this
 * class stages watch SQEs and settles their readiness CQEs. No selector, no
 * registry, no dispatcher hop lives here.
 */
class JvmReactorOperations : ReactorOperations {

    // One facade per reactor for watch submission; entries bound the watch count.
    private val watchFacade = openBackendFacade(64)
    private var nextToken = 0L

    // token -> (fd, interests, userData) for readiness mapping.
    private val watches = HashMap<Long, Triple<Int, Set<Interest>, Long>>()

    private fun interestMask(interests: Set<Interest>): Int {
        var mask = 0
        if (Interest.READ in interests) mask = mask or 0x1        // EPOLLIN
        if (Interest.WRITE in interests) mask = mask or 0x4       // EPOLLOUT
        if (Interest.CONNECT in interests) mask = mask or 0x2     // EPOLLRDHUP~connect
        return mask
    }

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        val token = ++nextToken
        watches[token] = Triple(fd, interests, userData)
        watchFacade.enqueue(
            UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = token, operationFlags = interestMask(interests))
        )
        watchFacade.submit()
    }

    override fun deregister(fd: Int) {
        val matches = watches.filterValues { it.first == fd }
        for ((token, _) in matches) {
            watchFacade.enqueue(UringSubmission(UringOp.POLL_REMOVE, fd, 0, 0, 0, userData = token))
            watches.remove(token)
        }
        if (matches.isNotEmpty()) watchFacade.submit()
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        val deadline = if (timeout.isInfinite()) -1L else System.currentTimeMillis() + timeout.inWholeMilliseconds
        val signals = ArrayList<ReactorSignal>()
        while (true) {
            // Settle readiness CQEs the backend substrate queued.
            for ((token, fd, mask) in backendDrain()) {
                val watch = watches[token] ?: continue
                val ready = mutableSetOf<Interest>()
                if (mask and 0x1 != 0) ready.add(Interest.READ)
                if (mask and 0x4 != 0) ready.add(Interest.WRITE)
                if (mask and 0x2 != 0) ready.add(Interest.CONNECT)
                if (mask and 0x1 != 0 && watch.second.contains(Interest.ACCEPT)) ready.add(Interest.ACCEPT)
                signals.add(ReactorSignal(watch.first, ready, watch.third))
            }
            if (signals.isNotEmpty()) return signals
            when {
                deadline == -1L -> kotlinx.coroutines.delay(10)
                System.currentTimeMillis() >= deadline -> return signals // empty on timeout
                else -> kotlinx.coroutines.delay(1)
            }
        }
    }
}

/** Per-reactor facade over the canonical probed backend. */
private fun openBackendFacade(entries: Int): borg.trikeshed.userspace.FunctionalUringFacade =
    borg.trikeshed.userspace.FunctionalUringFacade(
        entries,
        borg.trikeshed.userspace.openUserspaceChannelBackend(entries),
    )

private fun backendDrain(): List<Triple<Long, Int, Int>> =
    borg.trikeshed.userspace.JvmPollRegistry.drain()
