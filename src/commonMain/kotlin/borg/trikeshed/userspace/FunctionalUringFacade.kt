package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContext
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** OS effects only. Exactly one terminal CQE per SQE; completion order is unspecified.
 * Deferred operations retain their buffers until their terminal CQE is reaped.
 * Unsupported operations return -EOPNOTSUPP; failed effects return negative errno.
 */
public interface UserspaceChannelBackend {
    val capabilities: Long get() = 0L
    val nativeCapabilities: Long get() = 0L
    /** Operations whose CQE can arrive after [submitBatch] returns. */
    val deferredCapabilities: Long get() = 0L

    val availability: String get() = "emulated"

    val probeReport: UringProbeReport? get() = null
    /** Descriptor compatibility with this backend's registered kernel buffers. */
    fun supportsFixedBuffer(fd: Int): Boolean = true
    fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult>
    /** Consume deferred CQEs; zero polls, a positive minimum waits for completion. */
    fun reapCompletions(minComplete: Int = 0): List<SelectionResult> = emptyList()
    /** Quiesce deferred effects and make their terminal cancellation CQEs reapable. */
    fun cancelPending() {}
    suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion>

    /** Control-plane resource registration; fixed requests remain owned by commonMain. */
    fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> = unsupported()
    fun unregisterBuffers(): Result<Unit> = unsupported()

    fun close() {}
}

/**
 * Core dispatch layer for userspace channels.
 * Owns a bounded submission and completion ring of [entries] admissions.
 *
 * Compatibility callers enqueue operations, then call `submit()`.
 * [Key.create] owns bounded suspend batches; callers close it with [drain].
 */
public class FunctionalUringFacade(
    private val entries: Int,
    private val backend: UserspaceChannelBackend,
    private val containmentPolicy: borg.trikeshed.userspace.containment.ContainmentPolicy =
        borg.trikeshed.userspace.containment.ContainmentPolicy.MAXIMUM,
    ebpfPrograms: List<UringEbpfProgram> = emptyList(),
    scope: CoroutineScope? = null,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FunctionalUringFacade> {
        fun create(
            scope: CoroutineScope,
            entries: Int = 256,
            backend: UserspaceChannelBackend = openUserspaceChannelBackend(entries),
            ebpfPrograms: List<UringEbpfProgram> = emptyList(),
            containmentPolicy: borg.trikeshed.userspace.containment.ContainmentPolicy =
                borg.trikeshed.userspace.containment.ContainmentPolicy.MAXIMUM,
        ) = FunctionalUringFacade(entries, backend, containmentPolicy, ebpfPrograms, scope)
    }

    override val key: CoroutineContext.Key<*> get() = Key

    init {
        require(entries > 0) { "entries must be positive" }
        scope?.let { requireNotNull(it.coroutineContext[Job]) { "Uring requires an owning Job" }.ensureActive() }
    }

    private class Batch(val submissions: Series<UringSubmission>) {
        val result = Queue<Result<Series<UringCompletion>>>(1)
    }

    private val pending = ArrayDeque<UringSubmission>()
    private val inFlight = HashMap<Long, UringSubmission>()
    private val completions = ArrayDeque<SelectionResult>()
    private val submitPrograms = ebpfPrograms.filter { it.phase == UringEbpfPhase.SUBMIT }
    private val completionPrograms = ebpfPrograms.filter { it.phase == UringEbpfPhase.COMPLETE }
    private var closed = false
    private var closing = false
    private var closeFailure: Throwable? = null
    private val admission = Mutex()
    private val execution = Mutex()
    // The bound counts SQEs across batches, submitted effects and unreaped CQEs.
    private val input = Queue<Batch>(entries)
    private var capacityChanged = CompletableDeferred<Unit>()
    private val outstanding = HashSet<Long>()
    private var active = 0
    private val drained = CompletableDeferred<Unit>()
    private val termination = CompletableDeferred<Unit>()
    private val supervisor = scope?.let { SupervisorJob(it.coroutineContext[Job]) }

    val capabilities: Long get() = backend.capabilities
    val nativeCapabilities: Long get() = backend.nativeCapabilities
    val availability: String get() = backend.availability
    val probeReport: UringProbeReport? get() = backend.probeReport

    private val REJECTED_OPS: Set<UringOp> = containmentPolicy.layer2Metadata.rejectedXattrOps

    private val consumer = scope?.let {
        val ownedScope = CoroutineScope(it.coroutineContext + this + requireNotNull(supervisor))
        val cancellation = ownedScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { stopAdmission() }
            }
        }
        ownedScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val facade = requireNotNull(currentCoroutineContext()[Key])
            // Cancellation closes admission; the consumer retains every admitted buffer.
            withContext(NonCancellable) {
                try {
                    for (batch in input) {
                        try {
                            val result = runCatching { execution.withLock { facade.executeBatch(batch.submissions) } }
                            // Settlement releases the common borrow before publishing its result.
                            // drain must not depend on an external caller being scheduled again.
                            admission.withLock { release(batch.submissions) }
                            batch.result.trySend(result).getOrThrow()
                        } finally {
                            batch.result.close()
                        }
                    }
                } finally {
                    try {
                        stopAdmission()
                        execution.withLock { closeBackend() }
                        termination.complete(Unit)
                    } catch (failure: Throwable) {
                        termination.completeExceptionally(failure)
                    } finally {
                        cancellation.cancel()
                        requireNotNull(supervisor).complete()
                    }
                }
            }
        }
    }

    /**
     * Enqueue a raw io_uring submission.
     * Throws if the queue is full or if the op is a rejected xattr channel.
     */
    fun enqueue(submission: UringSubmission) = synchronous {
        check(!closing) { "Uring is draining or closed" }
        check(supervisor == null) { "Use batchEnqueue on a scoped uring" }
        require(submission.opcode !in REJECTED_OPS) {
            "xattr ops are deterministically rejected to close covert signaling channels: ${submission.opcode}"
        }
        require(occupancy() < entries) { "submission queue full" }
        require(submission.userData !in outstanding &&
            submission.userData !in inFlight &&
            pending.none { it.userData == submission.userData } && completions.none { it.userData == submission.userData }) {
            "Duplicate outstanding userData"
        }
        submission.memory?.retain()
        pending.addLast(submission)
    }

    // -- Typed API (sugar) --

    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        enqueue(UringOp.Companion.Submissions.read(file.id, 0L, buffer.remaining(), offset, userData).copy(buffer = buffer))
    }

    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        enqueue(UringOp.Companion.Submissions.write(file.id, 0L, buffer.remaining(), offset, userData).copy(buffer = buffer))
    }

    fun accept(file: FileImpl, userData: Long) {
        enqueue(UringOp.Companion.Submissions.accept(file.id, 0L, 0, userData))
    }

    fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        // Legion Doc 04 §3: "Hard-block any non-loopback network egress
        // unless directed to a strictly monitored proxy." Source of truth:
        // containmentPolicy.layer3Syscall.allowedEgress (defaults to loopback
        // only when policy is MAXIMUM).
        require(
            !containmentPolicy.layer3Syscall.allowedEgress.isEmpty() &&
                address in containmentPolicy.layer3Syscall.allowedEgress
        ) {
            "non-loopback CONNECT rejected by containment policy: $address:$port " +
                "(allowed=${containmentPolicy.layer3Syscall.allowedEgress})"
        }
        // This legacy signature has no encoded sockaddr to submit.
        throw UnsupportedOperationException("CONNECT requires an encoded socket address")
    }

    fun close(file: FileImpl, userData: Long) {
        enqueue(UringOp.Companion.Submissions.close(file.id, userData))
    }

    fun sync(file: FileImpl, userData: Long, metaData: Boolean) {
        enqueue(UringOp.Companion.Submissions.fsync(file.id, userData))
    }

    fun truncate(file: FileImpl, size: Long, userData: Long) {
        require(size >= 0)
        enqueue(UringSubmission(UringOp.FTRUNCATE, file.id, 0, 0, size, userData = userData))
    }

    // -- Completion drain --

    /** Suspend through the backend; never invoke the synchronous compatibility path. */
    suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        validate(submissions)
        val batchSubmissions = Array(submissions.size) { submissions[it] }.toSeries()
        currentCoroutineContext().ensureActive()
        if (batchSubmissions.size == 0) {
            admission.withLock { check(!closing) { "Uring is draining or closed" } }
            return 0 j { error("empty completion queue") }
        }
        if (supervisor == null) {
            reserveAwait(batchSubmissions) { active++ }
            try {
                val result = withContext(NonCancellable) {
                    runCatching { execution.withLock { executeBatch(batchSubmissions) } }
                }
                currentCoroutineContext().ensureActive()
                return result.getOrThrow()
            } finally {
                withContext(NonCancellable) {
                    admission.withLock {
                        release(batchSubmissions)
                        active--
                        if (closing && active == 0) drained.complete(Unit)
                    }
                }
            }
        }

        val batch = Batch(batchSubmissions)
        reserveAwait(batchSubmissions) { input.trySend(batch).getOrThrow() }
        // A cancelled caller may release its buffers only after its effects settle.
        val result = withContext(NonCancellable) { batch.result.receive() }
        currentCoroutineContext().ensureActive()
        return result.getOrThrow()
    }

    private suspend fun executeBatch(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val result = ArrayList<UringCompletion>(submissions.size)
        val admitted = partition(submissions, result)
        if (admitted.isNotEmpty()) {
            val cqes = backend.batchEnqueue(admitted.toSeries())
            val batchResults = ArrayList<UringCompletion>(admitted.size)
            admission.withLock {
                for (i in 0 until cqes.size) {
                    val cqe = cqes[i]
                    if (cqe.userData in inFlight) settle(listOf(SelectionResult(cqe.res, cqe.userData)))
                    else batchResults.add(cqe)
                }
            }
            correlate(admitted, batchResults.toSeries()) { result.add(it) }
        }
        val array = result.toTypedArray()
        return array.size j { array[it] }
    }

    /** Submit the prepared queue and suspend until every entry has completed. */
    suspend fun submitAwait(): Series<UringCompletion> {
        val batch = synchronous {
            check(!closing) { "Uring is draining or closed" }
            pending.toTypedArray().toSeries().also { submissions ->
                pending.clear()
                for (i in 0 until submissions.size) outstanding.add(submissions[i].userData)
                active++
            }
        }
        try {
            val result = withContext(NonCancellable) {
                runCatching { execution.withLock { executeBatch(batch) } }
            }
            currentCoroutineContext().ensureActive()
            return result.getOrThrow()
        } finally {
            withContext(NonCancellable) {
                admission.withLock {
                    release(batch)
                    active--
                    if (closing && active == 0) drained.complete(Unit)
                }
            }
        }
    }

    fun submit(): Int = synchronous {
        check(!closing) { "Uring is draining or closed" }
        submitPending()
    }

    private fun submitPending(): Int {
        val submissions = pending.toTypedArray()
        pending.clear()
        try {
            val rejected = ArrayList<UringCompletion>(submissions.size)
            val admitted = partition(submissions.toSeries(), rejected)
            for (submission in submissions) inFlight[submission.userData] = submission
            for (cqe in rejected) settle(listOf(SelectionResult(cqe.res, cqe.userData)), observe = false)
            if (admitted.isNotEmpty()) {
                settle(backend.submitBatch(admitted.asList()))
                check(admitted.none {
                    it.userData in inFlight && backend.deferredCapabilities and it.opcode.mask == 0L
                }) { "Backend lost submission completions" }
            }
            return submissions.size
        } catch (failure: Throwable) {
            // A failed call is terminal for its batch. Preserve every trustworthy CQE
            // already received and account for each remaining admission exactly once.
            closing = true
            input.close()
            capacityChanged.complete(Unit)
            for (submission in submissions) {
                if (completions.any { it.userData == submission.userData }) continue
                if (submission.userData in inFlight &&
                    backend.deferredCapabilities and submission.opcode.mask != 0L) continue
                inFlight.remove(submission.userData)
                completions.addLast(SelectionResult(-5, submission.userData))
                submission.memory?.release()
            }
            throw failure
        }
    }

    private fun settle(results: List<SelectionResult>, observe: Boolean = true) {
        var failure: Throwable? = null
        for (result in results) {
            val submission = inFlight.remove(result.userData)
            if (submission == null) {
                val invalid = IllegalStateException("Unknown or duplicate completion: ${result.userData}")
                if (failure == null) failure = invalid else failure.addSuppressed(invalid)
                continue
            }
            try {
                if (observe) {
                    val cqe = UringCompletion(result.userData, result.res, 0)
                    for (program in completionPrograms)
                        program.run(UringEbpfContext(UringEbpfPhase.COMPLETE, submission, cqe), result.res.toLong())
                }
            } catch (observerFailure: Throwable) {
                if (failure == null) failure = observerFailure else failure.addSuppressed(observerFailure)
            } finally {
                completions.addLast(result)
                submission.memory?.release()
            }
        }
        failure?.let { throw it }
    }

    private fun reap(minComplete: Int) {
        if (inFlight.isNotEmpty()) settle(backend.reapCompletions(minComplete.coerceAtMost(inFlight.size)))
    }

    private fun consumeCompletions(): List<SelectionResult> = buildList {
        while (completions.isNotEmpty()) add(completions.removeFirst())
        signalCapacity()
    }

    fun wait(minComplete: Int = 1): List<SelectionResult> = synchronous {
        require(minComplete >= 0) { "minComplete must be non-negative" }
        if (completions.size < minComplete && pending.isNotEmpty()) submitPending()
        reap((minComplete - completions.size).coerceAtLeast(0))
        consumeCompletions()
    }

    fun peek(): List<SelectionResult> = synchronous {
        reap(0)
        consumeCompletions()
    }

    fun closeNow() = synchronous {
        check(supervisor == null && active == 0) { "suspend drain() is required for active or scoped uring" }
        closing = true
        input.close()
        capacityChanged.complete(Unit)
        try {
            if (pending.isNotEmpty()) submitPending()
        } finally {
            closeBackend()
        }
    }

    private fun closeBackend() {
        if (!closed) {
            if (inFlight.isNotEmpty()) {
                backend.cancelPending()
                reap(0)
                check(inFlight.isEmpty()) { "Backend did not settle cancelled submissions" }
            }
            closed = true
            try {
                backend.close()
            } catch (failure: Throwable) {
                closeFailure = failure
            }
        }
        closeFailure?.let { throw it }
    }

    private suspend fun stopAdmission() = admission.withLock {
        closing = true
        input.close()
        capacityChanged.complete(Unit)
        backend.cancelPending()
        if (active == 0) drained.complete(Unit)
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        stopAdmission()
        if (consumer != null) {
            consumer.join()
            requireNotNull(supervisor).join()
            termination.await()
        } else {
            drained.await()
            execution.withLock {
                try {
                    if (pending.isNotEmpty()) submitPending()
                } finally {
                    closeBackend()
                }
            }
        }
    }

    suspend fun close() {
        drain()
    }

    private inline fun <T> synchronous(block: () -> T): T {
        check(admission.tryLock()) { "Concurrent use requires the suspend uring API" }
        try {
            check(execution.tryLock()) { "Concurrent use requires the suspend uring API" }
            try {
                return block()
            } finally {
                execution.unlock()
            }
        } finally {
            admission.unlock()
        }
    }

    private fun checkIdentities(submissions: Series<UringSubmission>) {
        for (i in 0 until submissions.size) {
            val userData = submissions[i].userData
            require(userData !in outstanding && userData !in inFlight && pending.none { it.userData == userData } &&
                completions.none { it.userData == userData }) { "Duplicate outstanding userData" }
        }
    }

    private fun reserve(submissions: Series<UringSubmission>) {
        checkIdentities(submissions)
        require(occupancy() + submissions.size <= entries) { "submission queue full" }
        var retained = 0
        try {
            for (i in 0 until submissions.size) {
                submissions[i].memory?.retain()
                retained++
            }
        } catch (failure: Throwable) {
            for (i in 0 until retained) submissions[i].memory?.release()
            throw failure
        }
        for (i in 0 until submissions.size) outstanding.add(submissions[i].userData)
    }

    private fun release(submissions: Series<UringSubmission>) {
        for (i in 0 until submissions.size) {
            outstanding.remove(submissions[i].userData)
            submissions[i].memory?.release()
        }
        signalCapacity()
    }

    private fun occupancy(): Int = pending.size + inFlight.size + completions.size + outstanding.size

    private fun signalCapacity() {
        val previous = capacityChanged
        capacityChanged = CompletableDeferred()
        previous.complete(Unit)
    }

    private suspend fun reserveAwait(submissions: Series<UringSubmission>, admitted: () -> Unit) {
        while (true) {
            val changed = admission.withLock {
                currentCoroutineContext().ensureActive()
                check(!closing && supervisor?.isActive != false) { "Uring is draining or closed" }
                checkIdentities(submissions)
                if (occupancy() + submissions.size <= entries) {
                    reserve(submissions)
                    try { admitted() } catch (failure: Throwable) {
                        release(submissions)
                        throw failure
                    }
                    null
                } else capacityChanged
            }
            if (changed == null) return
            changed.await()
        }
    }

    private fun validate(submissions: Series<UringSubmission>) {
        require(submissions.size in 0..entries) { "submission queue full" }
        val identities = HashSet<Long>()
        for (i in 0 until submissions.size) require(identities.add(submissions[i].userData)) { "Duplicate outstanding userData" }
    }
    private fun rejection(submission: UringSubmission): Int? {
        if (closing && backend.deferredCapabilities and submission.opcode.mask != 0L) return -125
        if (submission.opcode in containmentPolicy.layer2Metadata.rejectedXattrOps) return -13
        // SQE flags (links, multishot, fixed resources, etc.) require explicit semantics.
        if (submission.flags != 0 || (backend.capabilities and submission.opcode.mask) == 0L) return -95
        val context = UringEbpfContext(UringEbpfPhase.SUBMIT, submission, null)
        for (program in submitPrograms) {
            val value = program.run(context, 0)
            if (value < 0) return value.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
        }
        return null
    }
    private fun partition(submissions: Series<UringSubmission>, rejected: MutableList<UringCompletion>): Array<UringSubmission> {
        validate(submissions)
        val admitted = ArrayList<UringSubmission>(submissions.size)
        for (i in 0 until submissions.size) {
            val sqe = submissions[i]
            val error = rejection(sqe)
            if (error == null) admitted.add(sqe) else rejected.add(UringCompletion(sqe.userData, error, 0))
        }
        return admitted.toTypedArray()
    }
    private inline fun correlate(submissions: Array<UringSubmission>, results: Series<UringCompletion>, accept: (UringCompletion) -> Unit) {
        var failure: Throwable? = if (results.size == submissions.size) null
            else IllegalStateException("Backend lost submission completions")
        val byIdentity = submissions.associateByTo(HashMap(submissions.size)) { it.userData }
        for (i in 0 until results.size) {
            val cqe = results[i]
            val sqe = byIdentity.remove(cqe.userData)
            if (sqe == null) {
                val invalid = IllegalStateException("Unknown or duplicate completion: ${cqe.userData}")
                if (failure == null) failure = invalid else failure.addSuppressed(invalid)
                continue
            }
            // Completion programs observe actual results. Their return cannot alter
            // bytes transferred, errno, descriptor identity, or CQE flags after effects.
            accept(cqe)
            for (program in completionPrograms) try {
                program.run(UringEbpfContext(UringEbpfPhase.COMPLETE, sqe, cqe), cqe.res.toLong())
            } catch (observerFailure: Throwable) {
                if (failure == null) failure = observerFailure else failure.addSuppressed(observerFailure)
            }
        }
        failure?.let { throw it }
    }
}
