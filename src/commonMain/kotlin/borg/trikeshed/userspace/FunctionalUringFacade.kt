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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** OS effects only. Exactly one CQE per SQE; completion order is unspecified.
 * Buffers remain borrowed until the call settles, including on cancellation.
 * Unsupported operations return -EOPNOTSUPP; failed effects return negative errno.
 */
interface UserspaceChannelBackend {
    val capabilities: Long get() = 0L
    val nativeCapabilities: Long get() = 0L
    val availability: String get() = "emulated: no native binding"
    val probeReport: UringProbeReport? get() = null
    fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult>
    suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion>
    fun close() {}
}

/** The common SQE/CQE boundary. [Key.create] binds bounded admission and drain to
 * an owning scope. The constructor retains the synchronous compatibility API.
 * No operation ordering is inferred from userData or backend completion order.
 */
class FunctionalUringFacade(
    private val entries: Int,
    private val backend: UserspaceChannelBackend,
    private val containmentPolicy: ContainmentPolicy = ContainmentPolicy.MAXIMUM,
    private val ebpfPrograms: List<UringEbpfProgram> = emptyList(),
    scope: CoroutineScope? = null,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FunctionalUringFacade> {
        fun create(
            scope: CoroutineScope,
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
    private val pending = ArrayDeque<UringSubmission>()
    private val completions = ArrayDeque<SelectionResult>()
    private val submitPrograms = ebpfPrograms.filter { it.phase == UringEbpfPhase.SUBMIT }
    private val completionPrograms = ebpfPrograms.filter { it.phase == UringEbpfPhase.COMPLETE }
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
            }
        }
    }
    suspend fun close() = drain()

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
