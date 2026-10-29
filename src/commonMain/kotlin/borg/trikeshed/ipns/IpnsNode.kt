package borg.trikeshed.ipns

import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import borg.trikeshed.context.Drain
import borg.trikeshed.lib.Series
import borg.trikeshed.reactor.TlsCodecBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    private val leaseOwner: IpnsLeaseOwner? = null,
) : CoroutineContext.Element, Drain {
    init { requireNotNull(context[Job]) { "IPNS node requires an owning Job" } }
    companion object Key : CoroutineContext.Key<IpnsNode>
    val supervisor: CompletableJob = SupervisorJob(context[Job])
    override val key: CoroutineContext.Key<*> get() = Key
    private val requests = Channel<Request<*>>(16)
    private var journal: IpnsJournal? = null
    private var dialer: UringLibp2pDialer? = null
    private var publisher: IpnsPublisher? = null
    private var worker: Job? = null
    /** True from [open] until [drain] closes request admission. */
    val accepting: Boolean get() = worker != null && !requests.isClosedForSend

    private class Request<T>(val action: suspend () -> T, val result: CompletableDeferred<T>) {
        suspend fun execute() {
            try { result.complete(action()) }
            catch (failure: Throwable) {
                result.completeExceptionally(failure)
                if (failure is CancellationException) currentCoroutineContext().ensureActive()
            }
        }
    }

    suspend fun open() {
        check(worker == null && !requests.isClosedForSend) { "IPNS node already opened" }
        try {
            supervisor.ensureActive()
            val store = IpnsJournal.open(journalPath, crypto, leaseOwner).also { journal = it }
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
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Accepted requests finish under the node owner even if the HTTP client disconnects. */
    suspend fun <T> request(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        check(worker != null) { "IPNS node is not accepting requests" }
        val result = CompletableDeferred<T>()
        requests.send(Request(action, result))
        return result.await()
    }

    /** Repeatable: the publisher, journal and dialer each release once. */
    override suspend fun drain() { release() }

    private suspend fun release() = withContext(NonCancellable) {
        requests.close()
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
        failure?.let { throw it }
    }

    suspend fun close() = drain()
}
