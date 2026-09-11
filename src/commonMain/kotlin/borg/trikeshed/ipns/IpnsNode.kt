package borg.trikeshed.ipns

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Series
import borg.trikeshed.reactor.TlsCodecBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** Daemon-owned IPNS request admission, durable publisher and uring transport. */
class IpnsNode(
    private val journalPath: String,
    private val crypto: IpnsCrypto,
    private val bootstrap: Series<Libp2pPeer>,
    private val tlsBackend: (IpnsKeyPair) -> (ByteArray) -> TlsCodecBackend,
    private val context: CoroutineContext,
    private val limits: IpnsDhtLimits = IpnsDhtLimits(),
    private val policy: IpnsPublicationPolicy = IpnsPublicationPolicy(),
) : AsyncContextElement(parentJob = context[Job]) {
    init { requireNotNull(context[Job]) { "IPNS node requires an owning Job" } }
    companion object Key : CoroutineContext.Key<IpnsNode>
    override val key: CoroutineContext.Key<*> get() = Key
    private val admission = Mutex()
    private val termination = Mutex()
    private val requests = Channel<Request<*>>(16)
    private var journal: IpnsJournal? = null
    private var dialer: UringLibp2pDialer? = null
    private var publisher: IpnsPublisher? = null
    private var worker: Job? = null

    private class Request<T>(val action: suspend () -> T, val result: CompletableDeferred<T>) {
        suspend fun execute() {
            try { result.complete(action()) }
            catch (failure: Throwable) {
                result.completeExceptionally(failure)
                if (failure is CancellationException) currentCoroutineContext().ensureActive()
            }
        }
    }

    override suspend fun open() = termination.withLock {
        check(state == ElementState.CREATED) { "IPNS node already opened" }
        state = ElementState.OPEN
        try {
            supervisor.ensureActive()
            val store = IpnsJournal.open(journalPath, crypto).also { journal = it }
            val transport = UringLibp2pDialer(tlsBackend(store.identity), parentJob = supervisor,
                timeoutMillis = limits.rpcTimeoutMillis, maxConnections = limits.parallelism,
                context = context + this).also { dialer = it }
            val dht = IpnsDht(transport, crypto, bootstrap, limits)
            val publications = IpnsPublisher(store, dht, crypto, context + Dispatchers.Default + supervisor + this, policy)
                .also { publisher = it }
            publications.open()
            worker = CoroutineScope(context + Dispatchers.Default + supervisor + this + crypto + transport + dht + publications)
                .launch(start = CoroutineStart.UNDISPATCHED) {
                    try { for (request in requests) request.execute() }
                    finally {
                        requests.close()
                        while (true) {
                            val pending = requests.tryReceive().getOrNull() ?: break
                            pending.result.completeExceptionally(CancellationException("IPNS node stopped"))
                        }
                    }
                }
            supervisor.ensureActive()
            state = ElementState.ACTIVE
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Accepted requests finish under the node owner even if the HTTP client disconnects. */
    suspend fun <T> request(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val result = CompletableDeferred<T>()
        admission.withLock {
            check(state == ElementState.ACTIVE) { "IPNS node is not accepting requests" }
            requests.send(Request(action, result))
        }
        return result.await()
    }

    override suspend fun drain() = withContext(NonCancellable) {
        termination.withLock { if (state != ElementState.CLOSED) release() }
    }

    private suspend fun release() = withContext(NonCancellable) {
        admission.withLock {
            state = ElementState.DRAINING
            requests.close()
        }
        var failure: Throwable? = null
        suspend fun finish(action: suspend () -> Unit) {
            try { action() } catch (next: Throwable) {
                if (failure == null) failure = next else failure!!.addSuppressed(next)
            }
        }
        finish { worker?.join() }
        finish { publisher?.close() ?: journal?.close() }
        finish { dialer?.close() }
        supervisor.complete()
        supervisor.join()
        state = ElementState.CLOSED
        failure?.let { throw it }
    }

    override suspend fun close() = drain()
}
