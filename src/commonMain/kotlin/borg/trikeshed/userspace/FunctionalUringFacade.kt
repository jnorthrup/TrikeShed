package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.containment.ContainmentPolicy
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContext
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
<<<<<<< HEAD
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
=======
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
>>>>>>> codex/wire-nodejs-and-jvm-iouring
import kotlin.coroutines.CoroutineContext

/** OS effects only. Exactly one CQE per SQE; completion order is unspecified.
 * Buffers remain borrowed until the call settles, including on cancellation.
 * Unsupported operations return -EOPNOTSUPP; failed effects return negative errno.
 */
<<<<<<< HEAD
public interface UserspaceChannelBackend {
    val capabilities: Long get() = 0L
    val nativeCapabilities: Long get() = 0L
    val availability: String get() = "emulated"

    /**
     * Submit a batch of [UringSubmission] entries and return completions.
     *
     * In an ideal implementation, this maps directly to io_uring_submit().
     * In compatibility layers, this multiplexes Java NIO / JS Fetch / Wasm IO.
     */
=======
interface UserspaceChannelBackend {
    val capabilities: Long get() = 0L
    val nativeCapabilities: Long get() = 0L
    val availability: String get() = "emulated: no native binding"
    val probeReport: UringProbeReport? get() = null
>>>>>>> codex/wire-nodejs-and-jvm-iouring
    fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult>
    suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion>
<<<<<<< HEAD

    fun close() {}
}

/**
 * Core dispatch layer for userspace channels.
 * Maintains an internal [entries] queue similar to a `ring`.
 *
 * Compatibility callers enqueue operations, then call `submit()`.
 * [Key.create] owns bounded suspend batches; callers close it with [drain].
=======
    fun close() {}
}

/** The common SQE/CQE boundary. [Key.create] binds bounded admission and drain to
 * an owning scope. The constructor retains the synchronous compatibility API.
 * No operation ordering is inferred from userData or backend completion order.
>>>>>>> codex/wire-nodejs-and-jvm-iouring
 */
class FunctionalUringFacade(
    private val entries: Int,
    private val backend: UserspaceChannelBackend,
<<<<<<< HEAD
    private val containmentPolicy: borg.trikeshed.userspace.containment.ContainmentPolicy =
        borg.trikeshed.userspace.containment.ContainmentPolicy.MAXIMUM,
    ebpfPrograms: List<UringEbpfProgram> = emptyList(),
=======
    private val containmentPolicy: ContainmentPolicy = ContainmentPolicy.MAXIMUM,
    private val ebpfPrograms: List<UringEbpfProgram> = emptyList(),
>>>>>>> codex/wire-nodejs-and-jvm-iouring
    scope: CoroutineScope? = null,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FunctionalUringFacade> {
        fun create(
            scope: CoroutineScope,
<<<<<<< HEAD
            entries: Int,
            backend: UserspaceChannelBackend,
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

=======
            entries: Int = 256,
            backend: UserspaceChannelBackend = openUserspaceChannelBackend(entries),
            ebpfPrograms: List<UringEbpfProgram> = emptyList(),
            containmentPolicy: ContainmentPolicy = ContainmentPolicy.MAXIMUM,
        ) = FunctionalUringFacade(entries, backend, containmentPolicy, ebpfPrograms, scope)
    }
    override val key: CoroutineContext.Key<*> get() = Key
    val capabilities: Long get() = backend.capabilities
    val nativeCapabilities: Long get() = backend.nativeCapabilities
    val availability: String get() = backend.availability

    private class Batch(val submissions: Series<UringSubmission>, val caller: Job?) {
        val result = CompletableDeferred<Series<UringCompletion>>()
        val settled = CompletableDeferred<Unit>()
    }
>>>>>>> codex/wire-nodejs-and-jvm-iouring
    private val pending = ArrayDeque<UringSubmission>()
    private val completions = ArrayDeque<SelectionResult>()
    private val submitPrograms = ebpfPrograms.filter { it.phase == UringEbpfPhase.SUBMIT }
    private val completionPrograms = ebpfPrograms.filter { it.phase == UringEbpfPhase.COMPLETE }
<<<<<<< HEAD
    private var closed = false
    private var closing = false
    private var closeFailure: Throwable? = null
    private val admission = Mutex()
    private val execution = Mutex()
    // At most entries batches are admitted, each containing at most entries SQEs.
    private val input = Queue<Batch>(entries)
    private val capacity = Semaphore(entries)
    private var active = 0
    private val drained = CompletableDeferred<Unit>()
    private val termination = CompletableDeferred<Unit>()
    private val supervisor = scope?.let { SupervisorJob(it.coroutineContext[Job]) }

    val capabilities: Long get() = backend.capabilities
    val nativeCapabilities: Long get() = backend.nativeCapabilities
    val availability: String get() = backend.availability

    // -- Unified UringSubmission API --

    /**
     * Ops that are deterministically rejected to close covert signaling
     * channels (Legion Modelling Doc 04, Layer 2 — xattr is a documented
     * malleable surface for steganographic coordination per Doc 02 §1).
     * These are never submitted to the kernel; they fail immediately.
     *
     * Source of truth: [containmentPolicy.layer2Metadata.rejectedXattrOps].
     * When policy is MAXIMUM, this is the full 8-op xattr set.
     * Doc 04 §2: "Complete disabling or strict filtering of setxattr,
     * getxattr, listxattr, and removexattr."
     */
    private val REJECTED_OPS: Set<UringOp> =
        containmentPolicy.layer2Metadata.rejectedXattrOps

    /**
     * Ops whose metadata results are quantized to collapse micro-timing
     * side-channels (Legion Doc 04, Layer 2 §2 — "Deterministic Clock &
     * Inode Virtualization"). STATX returns synthetic, quantized timestamps.
     *
     * Active only when [containmentPolicy.layer2Metadata.quantizeTimestamps]
     * is true; otherwise the set is empty and completions pass through.
     */
    private val METADATA_QUANTIZED_OPS: Set<UringOp> =
        if (containmentPolicy.layer2Metadata.quantizeTimestamps) {
            setOf(
                UringOp.STATX,
                UringOp.FGETXATTR,
                UringOp.GETXATTR,
                UringOp.FLISTXATTR,
                UringOp.LISTXATTR,
                UringOp.GETDENTS
            )
        } else emptySet()

    /**
     * Quantization boundary for file timestamps (Doc 04 §2).
     * mtime/ctime/atime are rounded to the nearest epoch boundary.
     * 3600 = 1 hour — coarse enough to eliminate micro-timing encoding.
     */
    private val timestampQuantumSeconds: Long =
        containmentPolicy.layer2Metadata.timestampQuantumSeconds

    /**
     * Fixed synthetic epoch for quantized timestamps. All statx results
     * return mtime/ctime/atime = 0 unless overridden by policy.
     * Doc 04 §2: "or fixed to epoch 0, eliminating micro-timing."
     */
    private val syntheticEpoch: Long =
        containmentPolicy.layer2Metadata.syntheticEpoch

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
                            batch.result.trySend(result).getOrThrow()
                        } finally {
                            batch.result.close()
                            capacity.release()
                        }
                    }
                } finally {
                    stopAdmission()
                    try {
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
        require(pending.size < entries) { "submission queue full" }
        val rejected = runSubmitPrograms(submission)
        if (rejected != null) {
            completions.addLast(SelectionResult(rejected, submission.userData))
            return@synchronous
=======
    private val input = Queue<Batch>(entries.also { require(it > 0) }, onUndeliveredElement = {
        it.result.cancel()
        it.settled.complete(Unit)
    })
    private val execution = Mutex()
    private val admission = Mutex()
    private val drained = CompletableDeferred<Unit>()
    private val termination = CompletableDeferred<Unit>()
    private val outstanding = HashSet<Long>()
    private var active = 0
    private var closing = false
    private var closed = false
    private val supervisor = scope?.let { SupervisorJob(requireNotNull(it.coroutineContext[Job]) { "Uring requires an owning Job" }) }
    private val consumer = scope?.let {
        CoroutineScope(it.coroutineContext + this + supervisor!!).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (batch in input) {
                    if (batch.caller?.isActive == false) {
                        batch.result.cancel()
                        batch.settled.complete(Unit)
                        continue
                    }
                    try {
                        // Accepted effects must settle before caller cancellation releases buffers.
                        val result = withContext(NonCancellable) { execution.withLock { execute(batch.submissions) } }
                        batch.result.complete(result)
                    } catch (failure: Throwable) {
                        batch.result.completeExceptionally(failure)
                    } finally {
                        batch.settled.complete(Unit)
                    }
                }
            } finally {
                input.close()
                while (true) {
                    val batch = input.tryReceive().getOrNull() ?: break
                    batch.result.cancel()
                    batch.settled.complete(Unit)
                }
                try {
                    withContext(NonCancellable) { execution.withLock { closeBackend() } }
                    termination.complete(Unit)
                } catch (failure: Throwable) {
                    termination.completeExceptionally(failure)
                } finally {
                    supervisor!!.complete()
                }
            }
        }
    }

    /** Borrow the buffer until the corresponding CQE has been delivered. */
    fun enqueue(submission: UringSubmission) = synchronous {
        check(!closing && consumer == null) { "Use batchEnqueue on a scoped uring" }
        require(pending.size + completions.size < entries) { "submission queue full" }
        require(pending.none { it.userData == submission.userData } && completions.none { it.userData == submission.userData }) { "Duplicate outstanding userData" }
        require(submission.opcode !in containmentPolicy.layer2Metadata.rejectedXattrOps) {
            "Operation rejected by containment policy: ${submission.opcode}"
>>>>>>> codex/wire-nodejs-and-jvm-iouring
        }
        pending.addLast(submission)
    }

    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) =
        enqueue(UringOp.Companion.Submissions.read(file.id, 0, buffer.remaining(), offset, userData).copy(buffer = buffer))
    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) =
        enqueue(UringOp.Companion.Submissions.write(file.id, 0, buffer.remaining(), offset, userData).copy(buffer = buffer))
    fun accept(file: FileImpl, userData: Long) = enqueue(UringOp.Companion.Submissions.accept(file.id, 0, 0, userData))
    fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        require(address in containmentPolicy.layer3Syscall.allowedEgress) { "CONNECT rejected by containment policy" }
        // No portable sockaddr is encoded by this legacy signature.
        throw UnsupportedOperationException("CONNECT requires an encoded socket address")
    }
    fun close(file: FileImpl, userData: Long) = enqueue(UringOp.Companion.Submissions.close(file.id, userData))
    fun sync(file: FileImpl, userData: Long, metaData: Boolean) = enqueue(UringOp.Companion.Submissions.fsync(file.id, userData))
    fun truncate(file: FileImpl, size: Long, userData: Long) {
        require(size >= 0)
        enqueue(UringSubmission(UringOp.FTRUNCATE, file.id, 0, 0, size, userData = userData))
    }
    fun map(file: FileImpl, mode: String, position: Long, size: Long, userData: Long): Unit =
        throw UnsupportedOperationException("Memory mapping is not an SQE operation")

    /** A bounded batch is admitted as one queue item, containing at most entries SQEs.
     * Cancellation before execution skips the batch; cancellation during effects waits
     * for settlement. It does not claim that a completed write was undone.
     */
    suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
<<<<<<< HEAD
        require(submissions.size in 0..entries) { "submission queue full" }
        currentCoroutineContext().ensureActive()
        if (supervisor == null) {
            admission.withLock {
                currentCoroutineContext().ensureActive()
                check(!closing) { "Uring is draining or closed" }
                active++
            }
            try {
                val result = withContext(NonCancellable) {
                    runCatching { execution.withLock { executeBatch(submissions) } }
                }
                currentCoroutineContext().ensureActive()
                return result.getOrThrow()
            } finally {
                withContext(NonCancellable) {
                    admission.withLock {
                        active--
                        if (closing && active == 0) drained.complete(Unit)
                    }
                }
            }
        }

        admission.withLock { check(!closing && supervisor.isActive) { "Uring is draining or closed" } }
        capacity.acquire()
        var admitted = false
        try {
            val batch = admission.withLock {
                currentCoroutineContext().ensureActive()
                check(!closing && supervisor.isActive) { "Uring is draining or closed" }
                val batch = Batch(Array(submissions.size) { submissions[it] }.toSeries())
                input.trySend(batch).getOrThrow()
                admitted = true
                batch
            }
            // A cancelled caller may release its buffers only after its effects settle.
            val result = withContext(NonCancellable) { batch.result.receive() }
            currentCoroutineContext().ensureActive()
            return result.getOrThrow()
        } finally {
            if (!admitted) capacity.release()
        }
    }

    private suspend fun executeBatch(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val ordered = arrayOfNulls<UringCompletion>(submissions.size)
        val admitted = mutableListOf<UringSubmission>()
        val admittedIndexes = mutableListOf<Int>()
        for (i in 0 until submissions.size) {
            val submission = submissions[i]
            require(submission.opcode !in REJECTED_OPS) {
                "Operation rejected by containment policy: ${submission.opcode}"
            }
            val rejected = runSubmitPrograms(submission)
            if (rejected == null) {
                admitted += submission
                admittedIndexes += i
            } else {
                ordered[i] = UringCompletion(submission.userData, rejected, 0)
=======
        validate(submissions)
        if (consumer == null) {
            // Compatibility callers borrow their own coroutine scope for this operation.
            admission.withLock { check(!closing); active++ }
            try {
                currentCoroutineContext().ensureActive()
                return supervisorScope {
                    val delivery = Queue<Result<Series<UringCompletion>>>(1)
                    val worker = launch {
                        val result = runCatching {
                            execution.withLock {
                                currentCoroutineContext().ensureActive()
                                withContext(NonCancellable) { execute(submissions) }
                            }
                        }
                        delivery.send(result)
                    }
                    try { delivery.receive().getOrThrow() } finally {
                        withContext(NonCancellable) { worker.join(); delivery.close() }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    admission.withLock { active--; if (closing && active == 0) drained.complete(Unit) }
                }
>>>>>>> codex/wire-nodejs-and-jvm-iouring
            }
        }
        admission.withLock {
            for (i in 0 until submissions.size) require(submissions[i].userData !in outstanding) { "Duplicate outstanding userData" }
            for (i in 0 until submissions.size) outstanding.add(submissions[i].userData)
        }
        val batch = Batch(submissions, currentCoroutineContext()[Job])
        return try {
            input.send(batch)
            batch.result.await()
        } finally {
            withContext(NonCancellable) {
                batch.settled.await()
                admission.withLock { for (i in 0 until submissions.size) outstanding.remove(submissions[i].userData) }
            }
        }
    }

    suspend fun submitAwait(): Series<UringCompletion> {
<<<<<<< HEAD
        val batch = synchronous {
            check(!closing) { "Uring is draining or closed" }
            pending.toList().toSeries().also { pending.clear() }
        }
        return batchEnqueue(batch)
    }

    fun submit(): Int = synchronous {
        check(!closing) { "Uring is draining or closed" }
        submitPending()
    }

    private fun submitPending(): Int {
        val submitted = pending.size
        if (submitted == 0) return 0

        val unified = mutableListOf<UringSubmission>()
        while (pending.isNotEmpty()) {
            unified.add(pending.removeFirst())
        }

        if (unified.isNotEmpty()) {
            val results = backend.submitBatch(unified)
            check(results.size == unified.size) { "Backend lost submission completions" }
            val sanitized = results.mapIndexed { i, r -> sanitizeCompletion(unified[i], r) }
            completions.addAll(sanitized)
        }
        return submitted
    }

    fun wait(minComplete: Int = 1): List<SelectionResult> = synchronous {
        require(minComplete >= 0) { "minComplete must be non-negative" }
        if (completions.size < minComplete && pending.isNotEmpty()) submitPending()

        buildList {
            while (completions.isNotEmpty()) {
                add(completions.removeFirst())
=======
        val batch = synchronous { pending.toTypedArray().also { pending.clear() }.toSeries() }
        return batchEnqueue(batch)
    }

    suspend fun drain() = withContext(NonCancellable) {
        if (consumer != null) {
            input.close()
            consumer.join()
            supervisor!!.join()
            termination.await()
        } else {
            admission.withLock { closing = true; if (active == 0) drained.complete(Unit) }
            drained.await()
            execution.withLock {
                if (pending.isNotEmpty()) submitPending()
                closeBackend()
>>>>>>> codex/wire-nodejs-and-jvm-iouring
            }
        }
    }
    suspend fun close() = drain()

<<<<<<< HEAD
    fun peek(): List<SelectionResult> = synchronous {
        buildList {
            while (completions.isNotEmpty()) {
                add(completions.removeFirst())
            }
        }
    }

    fun closeNow() = synchronous {
        check(supervisor == null && active == 0) { "suspend drain() is required for active or scoped uring" }
        closing = true
        input.close()
        try {
            if (pending.isNotEmpty()) submitPending()
        } finally {
            closeBackend()
        }
    }

    private fun closeBackend() {
        if (!closed) {
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
=======
    /** Synchronous caller owns this facade exclusively. */
    fun submit(): Int = synchronous { check(!closing); submitPending() }
    fun wait(minComplete: Int = 1): List<SelectionResult> = synchronous {
        require(minComplete >= 0)
        if (completions.size < minComplete && pending.isNotEmpty()) submitPending()
        buildList { while (completions.isNotEmpty()) add(completions.removeFirst()) }
    }
    fun peek(): List<SelectionResult> = wait(0)
    fun closeNow() = synchronous {
        check(consumer == null && active == 0) { "Scoped uring must drain" }
        closing = true
        if (pending.isNotEmpty()) submitPending()
        closeBackend()
>>>>>>> codex/wire-nodejs-and-jvm-iouring
    }

    private inline fun <T> synchronous(block: () -> T): T {
        check(execution.tryLock()) { "Concurrent use requires a scoped uring" }
        try { return block() } finally { execution.unlock() }
    }
    private fun closeBackend() { if (!closed) { closing = true; backend.close(); closed = true } }
    private fun validate(submissions: Series<UringSubmission>) {
        require(submissions.size <= entries) { "submission queue full" }
        val identities = HashSet<Long>()
        for (i in 0 until submissions.size) require(identities.add(submissions[i].userData)) { "Duplicate outstanding userData" }
    }
    private fun rejection(submission: UringSubmission): Int? {
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
    private suspend fun execute(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val result = ArrayList<UringCompletion>(submissions.size)
        val admitted = partition(submissions, result)
        if (admitted.isNotEmpty()) {
            val cqes = backend.batchEnqueue(admitted.toSeries())
            correlate(admitted, cqes) { result.add(it) }
        }
        val array = result.toTypedArray()
        return array.size j { array[it] }
    }
    private fun submitPending(): Int {
        val submissions = pending.toTypedArray()
        pending.clear()
        val result = ArrayList<UringCompletion>(submissions.size)
        val admitted = partition(submissions.toSeries(), result)
        if (admitted.isNotEmpty()) {
            val cqes = backend.submitBatch(admitted.asList())
            correlate(admitted, cqes.size j { UringCompletion(cqes[it].userData, cqes[it].res, 0) }) { result.add(it) }
        }
        for (cqe in result) completions.addLast(SelectionResult(cqe.res, cqe.userData))
        return admitted.size
    }
    private inline fun correlate(submissions: Array<UringSubmission>, results: Series<UringCompletion>, accept: (UringCompletion) -> Unit) {
        check(results.size == submissions.size) { "Backend lost submission completions" }
        val byIdentity = submissions.associateByTo(HashMap(submissions.size)) { it.userData }
        for (i in 0 until results.size) {
            val cqe = results[i]
            val sqe = byIdentity.remove(cqe.userData) ?: error("Unknown or duplicate completion: ${cqe.userData}")
            // Completion programs observe actual results. Their return cannot alter
            // bytes transferred, errno, descriptor identity, or CQE flags after effects.
            for (program in completionPrograms) program.run(UringEbpfContext(UringEbpfPhase.COMPLETE, sqe, cqe), cqe.res.toLong())
            accept(cqe)
        }
    }
}
