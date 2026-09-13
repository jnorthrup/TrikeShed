package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.lib.toSeries
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Unified io_uring-style submission queue.
 *
 * Two APIs coexist:
 * 1. **Typed** — [read], [write], [accept], [connect], [close], [sync], [truncate] + [submit]/[wait]/[peek]
 * 2. **Unified** — [enqueue] any [UringSubmission], then [submit]/[wait]/[peek]
 *
 * The typed API is sugar that creates [UringSubmission] internally.
 * New code should use the unified path exclusively.
 *
 * Lifecycle: every channel is an [AsyncContextElement]. A channel built with a
 * parent Job rides the caller's supervision (the scoped facade owns its
 * consumer under that Job); a scopeless channel owns its own supervisor, so
 * [drain] settles in-flight work through the facade instead of abandoning it.
 */
class UringChannel internal constructor(
    private val facade: FunctionalUringFacade,
    parentJob: Job?,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    constructor(facade: FunctionalUringFacade) : this(facade, null)

    companion object Key : AsyncContextKey<UringChannel>() {
        /** Scoped channel: supervision flows from the caller's owning Job. */
        fun open(scope: CoroutineScope, facade: FunctionalUringFacade): UringChannel =
            UringChannel(facade, scope.coroutineContext[Job])
    }

    override val key: CoroutineContext.Key<*> get() = Key

    private fun jobOrNull(): kotlinx.coroutines.Job? = parentJob


    // Bridge queue: under an owned supervisor the facade's sync lane is closed
    // (the supervised consumer owns admission), so the sync-contract trio
    // accumulates here and [submit]/[wait] drive it through the suspend batch
    // contract on the owning Job — dispatch stays supervised, callers keep
    // their enqueue/submit/wait shape.
    private val bridged = ArrayDeque<UringSubmission>()
    private val bridgedResults = ArrayDeque<SelectionResult>()

    fun enqueue(submission: UringSubmission) {
        val job = jobOrNull()
        if (job == null) facade.enqueue(submission)
        else synchronized(bridged) {
            check(bridged.size < 4096) { "submission queue full" }
            bridged.addLast(submission)
        }
    }

    fun read(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        enqueue(UringOp.Companion.Submissions.read(file.impl.id, 0L, buffer.remaining(), offset, userData).copy(buffer = buffer))

    fun write(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        enqueue(UringOp.Companion.Submissions.write(file.impl.id, 0L, buffer.remaining(), offset, userData).copy(buffer = buffer))

    fun accept(file: File, userData: Long) =
        enqueue(UringOp.Companion.Submissions.accept(file.impl.id, 0L, 0, userData))

    fun connect(file: File, address: String, port: Int, userData: Long) =
        facade.connect(file.impl, address, port, userData)

    fun close(file: File, userData: Long) =
        enqueue(UringOp.Companion.Submissions.close(file.impl.id, userData))

    fun sync(file: File, userData: Long, metaData: Boolean) =
        enqueue(UringOp.Companion.Submissions.fsync(file.impl.id, userData))

    fun truncate(file: File, size: Int, userData: Long) =
        enqueue(UringSubmission(UringOp.FTRUNCATE, file.impl.id, 0, 0, size.toLong(), userData = userData))

    val capabilities: Long get() = facade.capabilities
    val nativeCapabilities: Long get() = facade.nativeCapabilities
    val availability: String get() = facade.availability
    fun closeNow() = facade.closeNow()

    /** FSM drain: stop admission, settle in-flight CQEs, join, then CLOSED. */
    override suspend fun drain() {
        open()
        facade.drain()
        super.drain()
    }

    override suspend fun close() {
        open()
        facade.close()
        super.close()
    }

    fun submit(): Int {
        val job = jobOrNull() ?: return facade.submit()
        val batch = synchronized(bridged) {
            val taken = bridged.toTypedArray()
            bridged.clear()
            taken
        }
        if (batch.isEmpty()) return 0
        val settled = kotlinx.coroutines.runBlocking(job) {
            facade.batchEnqueue(batch.toSeries())
        }
        synchronized(bridgedResults) {
            for (i in 0 until settled.a) {
                val cqe = settled.b(i)
                bridgedResults.addLast(SelectionResult(cqe.res, cqe.userData))
            }
        }
        return batch.size
    }

    suspend fun submitAwait() = facade.submitAwait()

    suspend fun batchEnqueue(submissions: borg.trikeshed.lib.Series<borg.trikeshed.userspace.UringOp.Companion.UringSubmission>) =
        facade.batchEnqueue(submissions)

    fun wait(minComplete: Int = 1): List<SelectionResult> {
        val job = jobOrNull() ?: return facade.wait(minComplete)
        submit()
        val results = ArrayList<SelectionResult>()
        synchronized(bridgedResults) {
            while (bridgedResults.isNotEmpty() && results.size < minComplete) {
                results.add(bridgedResults.removeFirst())
            }
        }
        return results
    }

    fun peek(): List<SelectionResult> = facade.peek()
}
