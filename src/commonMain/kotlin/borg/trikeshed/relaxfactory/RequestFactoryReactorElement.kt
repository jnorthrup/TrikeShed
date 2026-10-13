package borg.trikeshed.relaxfactory

import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

typealias RequestFactoryProcessor = suspend (String) -> String
typealias RequestFactoryClose = () -> Unit
typealias RequestFactoryDispatch = Join<String, CompletableDeferred<String>>

/** Serial admission for the platform RequestFactory processor; HTTP remains owned by HTX. */
class RequestFactoryReactorElement(
    val processor: RequestFactoryProcessor,
    val closeProcessor: RequestFactoryClose = {},
    val parentJob: Job? = null,
    val capacity: Int = 16,
) : RequestFactoryHandler, CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<RequestFactoryReactorElement>
    override val key: CoroutineContext.Key<*> get() = Key

    init { require(capacity > 0) { "RequestFactory capacity must be positive" } }

    var state: ElementState = ElementState.CREATED
        private set
    lateinit var supervisor: CompletableJob
        private set
    val requests = Channel<RequestFactoryDispatch>(capacity)
    val admission = Mutex()
    val termination = Mutex()
    var worker: Job? = null
        private set

    suspend fun open() = admission.withLock {
        check(state == ElementState.CREATED) { "RequestFactory processor already opened" }
        val context = currentCoroutineContext()
        context.ensureActive()
        supervisor = SupervisorJob(parentJob ?: context[Job])
        supervisor.ensureActive()
        state = ElementState.ACTIVE
        worker = CoroutineScope(context + supervisor + this).launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Throwable? = null
            try {
                for (dispatch in requests) {
                    try {
                        dispatch.b.complete(processor(dispatch.a))
                    } catch (cause: Throwable) {
                        dispatch.b.completeExceptionally(cause)
                        // A protocol/service failure costs its own request; owner cancellation stops admission.
                        if (cause is CancellationException) currentCoroutineContext().ensureActive()
                    }
                }
            } catch (cause: Throwable) {
                failure = cause
            } finally {
                // Closing first wakes any bounded sender holding the admission lock.
                requests.close(failure)
                withContext(NonCancellable) {
                    admission.withLock {
                        if (state != ElementState.CLOSED) state = ElementState.DRAINING
                    }
                    val cause = failure ?: CancellationException("RequestFactory processor stopped")
                    while (true) {
                        val dispatch = requests.tryReceive().getOrNull() ?: break
                        dispatch.b.completeExceptionally(cause)
                    }
                }
            }
        }
    }

    override suspend fun processRequest(payload: String): String {
        val receipt = CompletableDeferred<String>()
        admission.withLock {
            check(state == ElementState.ACTIVE) { "RequestFactory processor is not accepting requests" }
            requests.send(payload j receipt)
        }
        // Caller cancellation propagates, while every queued effect remains owned by the worker.
        return receipt.await()
    }

    suspend fun drain() = withContext(NonCancellable) {
        termination.withLock {
            if (state == ElementState.CLOSED) return@withLock
            admission.withLock {
                state = ElementState.DRAINING
                requests.close()
            }
            worker?.join()
            if (::supervisor.isInitialized) {
                supervisor.complete()
                supervisor.join()
            }
            try { closeProcessor() } finally { state = ElementState.CLOSED }
        }
    }

    suspend fun close() = drain()
}
