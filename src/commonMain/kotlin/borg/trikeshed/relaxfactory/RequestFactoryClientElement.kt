package borg.trikeshed.relaxfactory

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

typealias RequestFactoryClientReceipt = (Result<String>) -> Unit

/** Original RF payloads over an injected HTX client, admitted by the existing RF channel owner. */
class RequestFactoryClientElement(
    val endpoint: String,
    val client: HtxClientReactorElement,
    val parentJob: Job? = null,
    val capacity: Int = 16,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<RequestFactoryClientElement>
    override val key: CoroutineContext.Key<*> get() = Key

    private val binding = MutableStateFlow<RequestFactoryReactorElement?>(null)
    private val ownership = Mutex()
    private val callback = MutableStateFlow<Throwable?>(null)
    val callbackFailure: Throwable? get() = callback.value
    /** True once [drain] has closed the bound processor. */
    val closed: Boolean get() = binding.value?.closed?.isCompleted == true

    init {
        require(capacity > 0) { "RequestFactory client capacity must be positive" }
        parseHtxRequest(endpoint)
    }

    private fun processor(parent: Job?): RequestFactoryReactorElement = RequestFactoryReactorElement(
        processor = { payload ->
            val response = client.request(
                parseHtxRequest(endpoint, method = HtxMethod.POST, body = ByteSeries(payload))
                    .withHeader("Content-Type", "application/json; charset=utf-8")
                    .withHeader("Accept", "application/json"),
            )
            val bytes = response.body.toArray()
            if (response.status != 200) throw HtxHttpError(response.status, bytes.decodeToString())
            require(response.headers.headerValue("Content-Type")?.substringBefore(';')?.trim()?.lowercase() == "application/json") {
                "RequestFactory response requires application/json"
            }
            bytes.decodeToString(throwOnInvalidSequence = true)
        },
        parentJob = parent,
        capacity = capacity,
    )

    suspend fun open() = ownership.withLock {
        check(binding.value == null) { "RequestFactory client already opened or closed" }
        // Capture the caller owner before withContext creates the wrapper's temporary child job.
        val gate = processor(parentJob ?: currentCoroutineContext()[Job])
        binding.value = gate
        try { withContext(this) { gate.open() } }
        catch (failure: Throwable) {
            gate.drain()
            throw failure
        }
    }

    private fun deliver(receipt: RequestFactoryClientReceipt, result: Result<String>) {
        try { receipt(result) } catch (failure: Throwable) { callback.value = failure }
    }

    /** Exactly one callback, including immediate closed/full refusals; no coroutine per send. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun send(payload: String, receipt: RequestFactoryClientReceipt) {
        val gate = binding.value
        if (gate == null) {
            deliver(receipt, Result.failure(IllegalStateException("RequestFactory client is not open")))
            return
        }
        val completion = CompletableDeferred<String>()
        // Register before admission, so drain cannot finish ahead of an admitted callback.
        completion.invokeOnCompletion { failure ->
            deliver(receipt, if (failure == null) Result.success(completion.getCompleted()) else Result.failure(failure))
        }
        gate.tryProcessRequest(payload, completion)
    }

    suspend fun exchange(payload: String): String {
        val receipt = CompletableDeferred<String>()
        send(payload) { result -> result.fold(receipt::complete, receipt::completeExceptionally) }
        return receipt.await()
    }

    suspend fun drain() = withContext(NonCancellable) {
        ownership.withLock {
            val gate = binding.value ?: processor(parentJob).also { binding.value = it }
            gate.drain()
        }
    }

    suspend fun close() = drain()
}
