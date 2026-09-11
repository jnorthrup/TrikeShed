package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/** A byte-free observation sleeve at the shared backend boundary, after facade admission. */
interface UringTrace : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<UringTrace>
    override val key: CoroutineContext.Key<*> get() = Key
    fun channel(id: Long, availability: String, nativeCapabilities: Long)
    fun submit(channel: Long, submission: UringSubmission)
    fun complete(channel: Long, submission: UringSubmission, result: Int)
    fun failed(channel: Long, submission: UringSubmission, failure: String)
}

@OptIn(ExperimentalAtomicApi::class)
private val traceChannel = AtomicLong(1)

@OptIn(ExperimentalAtomicApi::class)
internal class UringTraceBackend(
    private val backend: UserspaceChannelBackend,
    private val trace: UringTrace,
) : UserspaceChannelBackend by backend {
    private val id = traceChannel.fetchAndAdd(1)
    init { trace.channel(id, backend.availability, backend.nativeCapabilities) }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        submissions.forEach { trace.submit(id, it) }
        return try {
            backend.submitBatch(submissions).also { completed ->
                val source = submissions.associateBy { it.userData }
                completed.forEach { result -> source[result.userData]?.let { trace.complete(id, it, result.res) } }
            }
        } catch (failure: Throwable) {
            submissions.forEach { trace.failed(id, it, failure.toString()) }
            throw failure
        }
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        for (i in 0 until submissions.size) trace.submit(id, submissions[i])
        return try {
            backend.batchEnqueue(submissions).also { results ->
                val source = mutableMapOf<Long, UringSubmission>()
                for (i in 0 until submissions.size) source[submissions[i].userData] = submissions[i]
                for (i in 0 until results.size) source[results[i].userData]?.let { trace.complete(id, it, results[i].res) }
            }
        } catch (failure: Throwable) {
            for (i in 0 until submissions.size) trace.failed(id, submissions[i], failure.toString())
            throw failure
        }
    }
}
