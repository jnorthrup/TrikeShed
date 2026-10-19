package borg.trikeshed.userspace.benchmark

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/** Fan in publication and observation; the observation captures its timestamp before publisher return. */
private suspend fun wakeLatency(observation: Deferred<Long>, publication: Deferred<Long>): Long {
    select<Unit> {
        observation.onAwait { }
        publication.onAwait { }
    }
    val observed = observation.await()
    val published = publication.await()
    return (observed - published).also { check(it >= 0) { "Observation preceded fixture publication" } }
}

/**
 * Native READ baseline, independent of database/reactor synchronization policy. [producer] publishes exactly one
 * FIFO byte and returns the monotonic timestamp immediately before publication; [nowNanos] uses the same clock.
 * The bounded gate observes entry to the completion wait, not the instant the OS thread parks. A separate syscall
 * profile must verify blocking waits. Samples cover publication to facade completion return, not CQE-to-scheduler time.
 * This function owns and drains [backend]; the caller owns [fd] and the producer fixture's resources.
 */
suspend fun uringCompletionWakeBenchmark(
    backend: UserspaceChannelBackend,
    fd: Int,
    warmup: Int,
    iterations: Int,
    producer: suspend (Int) -> Long,
    nowNanos: () -> Long,
    phaseBoundary: ((String, Boolean) -> Unit)? = null,
    producerContext: CoroutineContext = Dispatchers.Default,
): LongArray = coroutineScope {
    require(warmup >= 0 && iterations > 0)
    val owner = SupervisorJob(currentCoroutineContext()[Job])
    val scope = CoroutineScope(currentCoroutineContext() + owner)
    val gate = Channel<Long>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    var token = 0L
    var waited = 0L
    val observedBackend = object : UserspaceChannelBackend by backend {
        override fun submitDeferredBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            token = submissions.single().userData
            return backend.submitDeferredBatch(submissions)
        }
        override suspend fun awaitCompletions() {
            waited = token
            gate.trySend(token).getOrThrow()
            backend.awaitCompletions()
        }
    }
    val ring = FunctionalUringFacade.create(scope, 1, observedBackend)
    var finished = false
    try {
        check(backend.nativeCapabilities and UringOp.READ.mask != 0L) { "Wake baseline requires native READ execution" }
        val samples = LongArray(iterations)
        val bytes = ByteBuffer(1)
        repeat(warmup + iterations) { iteration ->
            if (iteration == warmup) phaseBoundary?.invoke("native_wake", true)
            val expected = iteration.toLong() + 1
            bytes.clear()
            val observation = scope.async(start = CoroutineStart.UNDISPATCHED) {
                val completion = ring.batchEnqueue(s_[Submissions.read(fd, 0, 1, -1, expected).copy(buffer = bytes)])[0]
                val observed = nowNanos()
                check(completion.userData == expected && completion.res == 1 && bytes.position() == 1) {
                    "Native wake READ failed: $completion"
                }
                check(waited == expected) { "FIFO was readable before completion-wait admission" }
                observed
            }
            val publication = scope.async(producerContext) {
                while (gate.receive() != expected) { /* coalesced readiness from the preceding iteration */ }
                producer(iteration)
            }
            val latency = wakeLatency(observation, publication)
            if (iteration >= warmup) samples[iteration - warmup] = latency
        }
        phaseBoundary?.invoke("native_wake", false)
        finished = true
        samples
    } finally {
        withContext(NonCancellable) {
            try {
                if (!finished) backend.cancelPending()
                ring.drain()
            } finally {
                gate.close()
                owner.complete()
                owner.join()
            }
        }
    }
}

/**
 * Reactor state-policy comparison. Current JVM POLL_ADD watches use the POSIX adapter even when file READ/WRITE
 * execute through a native ring; label that path explicitly. [consume] must drain the fixture byte through the common
 * NIO/uring facade after each observation. The caller owns and drains [reactor], its descriptor and the consume facade.
 */
suspend fun reactorWakeBenchmark(
    reactor: ReactorOperations,
    fd: Int,
    warmup: Int,
    iterations: Int,
    producer: suspend (Int) -> Long,
    consume: suspend () -> Unit,
    nowNanos: () -> Long,
    phaseBoundary: ((String, Boolean) -> Unit)? = null,
    producerContext: CoroutineContext = Dispatchers.Default,
): LongArray = coroutineScope {
    require(warmup >= 0 && iterations > 0)
    val owner = SupervisorJob(currentCoroutineContext()[Job])
    val scope = CoroutineScope(currentCoroutineContext() + owner + reactor)
    val gate = Channel<Unit>(1)
    var pendingObservation: Deferred<Long>? = null
    try {
        reactor.register(fd, setOf(Interest.READ), 1)
        val samples = LongArray(iterations)
        repeat(warmup + iterations) { iteration ->
            if (iteration == warmup) phaseBoundary?.invoke("reactor_wake", true)
            val observation = scope.async(start = CoroutineStart.UNDISPATCHED) {
                val signals = requireNotNull(currentCoroutineContext()[ReactorOperations]).poll(Duration.INFINITE)
                val observed = nowNanos()
                check(signals.size == 1 && signals[0].fd == fd && Interest.READ in signals[0].ready) {
                    "Unexpected reactor wake signals: $signals"
                }
                observed
            }
            pendingObservation = observation
            check(!observation.isCompleted) { "FIFO was readable before the reactor wait" }
            gate.send(Unit)
            val publication = scope.async(producerContext) {
                gate.receive()
                producer(iteration)
            }
            val latency = wakeLatency(observation, publication)
            pendingObservation = null
            consume()
            if (iteration >= warmup) samples[iteration - warmup] = latency
        }
        phaseBoundary?.invoke("reactor_wake", false)
        samples
    } finally {
        withContext(NonCancellable) {
            gate.close()
            pendingObservation?.cancelAndJoin()
            owner.complete()
            owner.join()
            reactor.deregister(fd)
        }
    }
}
