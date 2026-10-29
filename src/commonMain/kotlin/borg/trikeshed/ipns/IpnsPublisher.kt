package borg.trikeshed.ipns

import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import borg.trikeshed.context.Drain
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.milliseconds

/** IPNS publication cadence; TTL is a resolver cache hint, lifetime is signed EOL. */
data class IpnsPublicationPolicy(
    val lifetime: Duration = 48.hours,
    val ttl: Duration = 5.minutes,
    val republishInterval: Duration = 4.hours,
    val retryInterval: Duration = 5.minutes,
) {
    init {
        require(lifetime.isPositive() && lifetime.isFinite())
        require(ttl.isPositive() && ttl <= lifetime)
        require(republishInterval.isPositive() && republishInterval < lifetime)
        require(retryInterval.isPositive() && retryInterval < lifetime)
    }
}

/** A bounded publication worker serializes durable versions before DHT fan-out. */
class IpnsPublisher(
    private val journal: IpnsJournal,
    private val dht: IpnsDht,
    private val crypto: IpnsCrypto,
    private val context: CoroutineContext,
    val policy: IpnsPublicationPolicy = IpnsPublicationPolicy(),
    private val now: () -> Instant = { Clock.System.now() },
) : CoroutineContext.Element, Drain {
    companion object Key : CoroutineContext.Key<IpnsPublisher>
    override val key: CoroutineContext.Key<*> get() = Key
    val supervisor: CompletableJob = SupervisorJob(context[Job])
    /** Completes once [drain] has closed the journal. */
    val closed: CompletableJob = Job()
    val name: IpnsName get() = journal.identity.name
    val identity: IpnsKeyPair get() = journal.identity
    val latest: IpnsRecord? get() = journal.latest
    var lastReport: IpnsPublishReport? = null
        private set
    var lastFailure: Throwable? = null
        private set
    private class Request(val value: String?, val reply: CompletableDeferred<IpnsPublishReport>)
    private val requests = Channel<Request>(16)
    /** Closed once, by the first [drain]; the worker stops its schedule when it closes. */
    private val stop = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    suspend fun open() {
        check(worker == null && !requests.isClosedForSend) { "IPNS publisher already opened" }
        worker = CoroutineScope(context + supervisor + this + crypto).launch(start = CoroutineStart.UNDISPATCHED) {
            var interval = if (journal.latest == null) policy.republishInterval else 1.milliseconds
            try {
            while (true) {
                val request = select<Request?> {
                    requests.onReceiveCatching { it.getOrNull() }
                    onTimeout(interval.inWholeMilliseconds) {
                        Request(null, CompletableDeferred())
                    }
                    stop.onReceiveCatching { null }
                } ?: break
                try {
                    val report = execute(request.value)
                    lastReport = report
                    lastFailure = null
                    request.reply.complete(report)
                    interval = if (report.complete) policy.republishInterval else policy.retryInterval
                } catch (failure: Throwable) {
                    lastFailure = failure
                    request.reply.completeExceptionally(failure)
                    if (failure is CancellationException) currentCoroutineContext().ensureActive()
                    interval = policy.retryInterval
                }
            }
            for (request in requests) {
                try { request.reply.complete(execute(request.value)) }
                catch (failure: Throwable) {
                    request.reply.completeExceptionally(failure)
                    if (failure is CancellationException) currentCoroutineContext().ensureActive()
                }
            }
            } finally {
                requests.close()
                while (true) {
                    val rejected = requests.tryReceive().getOrNull() ?: break
                    rejected.reply.completeExceptionally(CancellationException("IPNS publisher stopped"))
                }
            }
        }
    }

    suspend fun publish(value: String): IpnsPublishReport = submit(value)
    suspend fun publish(cid: ContentId): IpnsPublishReport = publish("/ipfs/" + cid.toIpnsCid(0x55u))
    suspend fun republish(): IpnsPublishReport = submit(null)

    private suspend fun submit(value: String?): IpnsPublishReport {
        check(worker != null) { "IPNS publisher is not accepting work" }
        val reply = CompletableDeferred<IpnsPublishReport>()
        requests.send(Request(value, reply))
        return reply.await()
    }

    private suspend fun execute(value: String?): IpnsPublishReport {
        val current = journal.latest
        val time = now()
        val path = value ?: current?.value ?: error("No IPNS record has been published")
        val renew = current == null || value != null || current.validUntil - time <= policy.republishInterval
        val record = if (renew) {
            val sequence = if (current == null) 0uL else if (path == current.value) current.sequence else {
                check(current.sequence != ULong.MAX_VALUE) { "IPNS sequence exhausted" }
                current.sequence + 1uL
            }
            val expiry = maxOf(time + policy.lifetime, current?.validUntil?.plus(1.nanoseconds) ?: time)
            IpnsRecord.create(journal.identity, path, sequence, expiry,
                policy.ttl.inWholeNanoseconds.toULong(), crypto).also(journal::append)
        } else current!!
        return dht.publish(name, record.bytes, time)
    }

    override suspend fun drain() = withContext(NonCancellable) {
        if (!stop.close()) return@withContext closed.join()
        requests.close()
        worker?.join()
        supervisor.complete()
        supervisor.join()
        try { journal.close() } finally { closed.complete() }
    }

    suspend fun close() = drain()
}
