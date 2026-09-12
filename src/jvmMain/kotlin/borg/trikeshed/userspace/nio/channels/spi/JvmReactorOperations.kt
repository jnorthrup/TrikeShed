package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Readiness is a one-shot POLL_ADD CQE; persistent interests rearm after delivery. */
class JvmReactorOperations : AsyncContextElement(), ReactorOperations {
    override val key get() = ReactorOperations.Key

    private val watchFacade = FunctionalUringFacade(64, openUserspaceChannelBackend(64))
    private var nextToken = 0L
    private val watches = HashMap<Int, Pair<Set<Interest>, Long>>()
    private val tokens = HashMap<Long, Int>()
    private val signals = ArrayDeque<ReactorSignal>()

    private fun interestMask(interests: Set<Interest>): Int {
        var mask = 0
        if (Interest.READ in interests || Interest.ACCEPT in interests) mask = mask or 0x1
        if (Interest.WRITE in interests || Interest.CONNECT in interests) mask = mask or 0x4
        return mask
    }

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        check(state < ElementState.DRAINING) { "Reactor is draining or closed" }
        if (interests.isEmpty()) {
            deregister(fd)
            return
        }
        if (watches[fd] == (interests to userData)) return
        deregister(fd)
        // The remaining slot admits POLL_REMOVE while every watch is pending.
        check(watches.size < 63) { "Reactor watch capacity exceeded" }
        watches[fd] = interests.toSet() to userData
        try {
            submitWatch(fd)
            collect()
        } catch (failure: Throwable) {
            watches.remove(fd)
            throw failure
        }
    }

    private fun submitWatch(fd: Int) {
        val watch = watches.getValue(fd)
        val token = ++nextToken
        watchFacade.enqueue(
            UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = token, operationFlags = interestMask(watch.first)),
        )
        tokens[token] = fd
        check(watchFacade.submit() == 1) { "POLL_ADD SQE was not submitted" }
    }

    override fun deregister(fd: Int) {
        watches.remove(fd)
        signals.removeAll { it.fd == fd }
        val token = tokens.entries.firstOrNull { it.value == fd }?.key ?: return
        watchFacade.enqueue(
            UringSubmission(UringOp.POLL_REMOVE, fd, token, 0, 0, userData = ++nextToken),
        )
        check(watchFacade.submit() == 1) { "POLL_REMOVE SQE was not submitted" }
        collect()
    }

    private fun collect() {
        for (cqe in watchFacade.wait(0)) {
            val fd = tokens.remove(cqe.userData) ?: continue
            val watch = watches[fd] ?: continue
            check(cqe.res >= 0) { "POLL_ADD failed for fd=$fd: ${cqe.res}" }
            val ready = mutableSetOf<Interest>()
            if (cqe.res and 0x1 != 0) {
                if (Interest.READ in watch.first) ready.add(Interest.READ)
                if (Interest.ACCEPT in watch.first) ready.add(Interest.ACCEPT)
            }
            if (cqe.res and 0x4 != 0) {
                if (Interest.WRITE in watch.first) ready.add(Interest.WRITE)
                if (Interest.CONNECT in watch.first) ready.add(Interest.CONNECT)
            }
            // Error/hangup wakes the requested operation so its CQE reports the cause.
            if (cqe.res and (0x8 or 0x10 or 0x20) != 0) ready.addAll(watch.first)
            if (ready.isNotEmpty()) signals.addLast(ReactorSignal(fd, ready, watch.second))
        }
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        check(state < ElementState.DRAINING) { "Reactor is draining or closed" }
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            collect()
            if (signals.isNotEmpty()) return buildList {
                while (signals.isNotEmpty()) add(signals.removeFirst())
            }
            for (fd in watches.keys) {
                if (fd !in tokens.values) submitWatch(fd)
            }
            collect()
            if (signals.isNotEmpty()) continue
            if (!timeout.isInfinite() && started.elapsedNow() >= timeout) return emptyList()
            delay(1)
        }
    }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        if (state == ElementState.CLOSED) return@withContext
        state = ElementState.DRAINING
        try {
            for (fd in watches.keys.toIntArray()) deregister(fd)
            watchFacade.closeNow()
        } finally {
            watches.clear()
            tokens.clear()
            signals.clear()
            supervisor.complete()
            supervisor.join()
            state = ElementState.CLOSED
        }
    }

    override suspend fun close() = drain()
}
