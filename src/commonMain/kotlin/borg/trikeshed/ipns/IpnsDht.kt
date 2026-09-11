package borg.trikeshed.ipns

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

data class IpnsDhtLimits(
    val replication: Int = 20,
    val parallelism: Int = 10,
    val maxQueries: Int = 128,
    val maxCandidates: Int = 256,
    val rpcTimeoutMillis: Long = 15_000,
    val operationTimeoutMillis: Long = 120_000,
    val minValidResponses: Int = 16,
    val allowPrivateAddresses: Boolean = false,
    val readRepair: Boolean = true
) {
    init {
        require(replication in 1..20 && parallelism in 1..20)
        require(maxQueries in replication..4096 && maxCandidates in replication..4096)
        require(rpcTimeoutMillis in 1..120_000 && operationTimeoutMillis in rpcTimeoutMillis..3_600_000)
        require(minValidResponses in 1..replication)
    }
}

data class IpnsDhtFailure(val peer: Libp2pPeer, val stage: IpnsDhtType, val reason: String)
data class IpnsDhtReply(val peer: Libp2pPeer, val message: IpnsDhtMessage?, val failure: IpnsDhtFailure?)
data class IpnsLookupReport(
    val closest: Series<Libp2pPeer>,
    val replies: Series<IpnsDhtReply>,
    val exhausted: Boolean,
    val queryLimitReached: Boolean,
    val candidateLimitReached: Boolean
)
data class IpnsPublishReport(
    val record: IpnsRecord,
    val lookup: IpnsLookupReport,
    val acknowledgements: Series<Libp2pPeer>,
    val failures: Series<IpnsDhtFailure>,
    val replicaTarget: Int,
    val primaryAcknowledgements: Series<Libp2pPeer> = acknowledgements,
    val replacementAcknowledgements: Series<Libp2pPeer> = 0 j { error("Empty acknowledgements") },
    val putAttempts: Int = lookup.closest.size
) {
    val complete: Boolean get() = lookup.exhausted && !lookup.queryLimitReached && acknowledgements.size >= replicaTarget
}
data class IpnsDhtValue(val peer: Libp2pPeer, val record: IpnsRecord)
data class IpnsResolveReport(
    val lookup: IpnsLookupReport,
    val selected: IpnsRecord?,
    val valid: Series<IpnsDhtValue>,
    val missing: Series<Libp2pPeer>,
    val failures: Series<IpnsDhtFailure>,
    val quorumRequired: Int,
    val repairs: Series<IpnsDhtReply>
) {
    val quorumReached: Boolean get() = valid.size >= quorumRequired
    val absent: Boolean get() = selected == null && missing.size > 0 && failures.size == 0 && lookup.exhausted && !lookup.candidateLimitReached
}

class IpnsDhtResolutionException(val report: IpnsResolveReport) : IllegalStateException(
    "IPNS resolution incomplete: ${report.valid.size}/${report.quorumRequired} valid peer responses, ${report.failures.size} failures"
)

/** Client-mode Amino DHT. Every RPC uses an authenticated, explicitly configured libp2p dialer. */
class IpnsDht(
    val dialer: Libp2pDialer,
    val crypto: IpnsCrypto,
    bootstrap: Series<Libp2pPeer>,
    val limits: IpnsDhtLimits = IpnsDhtLimits()
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<IpnsDht> {
        fun distance(left: ByteArray, right: ByteArray): ByteArray {
            require(left.size == 32 && right.size == 32)
            return ByteArray(32) { (left[it].toInt() xor right[it].toInt()).toByte() }
        }
        fun compareDistance(left: ByteArray, right: ByteArray): Int {
            require(left.size == right.size)
            for (i in left.indices) {
                val difference = (left[i].toInt() and 255) - (right[i].toInt() and 255)
                if (difference != 0) return difference
            }
            return 0
        }
    }
    override val key: CoroutineContext.Key<*> get() = Key
    val bootstrap: Series<Libp2pPeer> = bootstrap.view.map {
        IpnsDhtAddresses.filter(it, limits.allowPrivateAddresses)
            ?: throw IllegalArgumentException("Bootstrap peer has no permitted numeric TCP address")
    }.toSeries()

    init { require(this.bootstrap.size in 1..limits.maxCandidates) { "Explicit bounded bootstrap peers required" } }

    suspend fun findClosest(key: ByteArray): IpnsLookupReport {
        val snapshot = key.copyOf()
        return operation { walk(snapshot, IpnsDhtType.FIND_NODE) }
    }

    suspend fun publish(name: IpnsName, record: ByteArray, now: Instant): IpnsPublishReport = operation {
        val verified = IpnsRecord.verify(name, record, now, currentCoroutineContext()[IpnsCrypto]!!)
        val wire = verified.bytes
        val lookup = walk(name.routingKey, IpnsDhtType.FIND_NODE)
        val request = IpnsDhtMessage(IpnsDhtType.PUT_VALUE, name.routingKey, IpnsDhtRecord(name.routingKey, wire))
        val replies = dispatch(lookup.closest, request)
        val acknowledgements = mutableListOf<Libp2pPeer>()
        val primary = mutableListOf<Libp2pPeer>()
        val replacements = mutableListOf<Libp2pPeer>()
        val failures = mutableListOf<IpnsDhtFailure>()
        for (reply in lookup.replies.view) reply.failure?.let(failures::add)
        for (reply in replies.view) {
            if (reply.failure != null) failures += reply.failure
            else {
                acknowledgements += reply.peer // rpc() checks the complete echoed key/value before success.
                primary += reply.peer
            }
        }
        // Routing reachability does not imply that a peer accepts storage. Refill failed slots from
        // already queried peers only, nearest first; no new lookup and no repeated PUT to the same ID.
        val attempted = lookup.closest.view.map { IpnsEncoding.base58(it.id) }.toMutableSet()
        val target = IpnsEncoding.sha256(name.routingKey)
        val reserve = lookup.replies.view.filter { it.failure == null }.map { it.peer }
            .distinctBy { IpnsEncoding.base58(it.id) }.filter { IpnsEncoding.base58(it.id) !in attempted }
            .map { Candidate(it, distance(IpnsEncoding.sha256(it.id), target)) }
            .sortedWith { a, b -> compareDistance(a.distance, b.distance) }.toSeries()
        var offset = 0
        while (acknowledgements.size < limits.replication && offset < reserve.size && attempted.size < limits.maxQueries) {
            val start = offset
            val width = minOf(limits.replication - acknowledgements.size, reserve.size - offset, limits.maxQueries - attempted.size)
            val batch = width j { index: Int -> reserve[start + index].peer }
            for (peer in batch.view) check(attempted.add(IpnsEncoding.base58(peer.id)))
            for (reply in dispatch(batch, request).view) {
                if (reply.failure != null) failures += reply.failure
                else {
                    acknowledgements += reply.peer
                    replacements += reply.peer
                }
            }
            offset += width
        }
        IpnsPublishReport(verified, lookup, acknowledgements.toSeries(), failures.toSeries(), limits.replication,
            primary.toSeries(), replacements.toSeries(), attempted.size)
    }

    /** Below-quorum values remain available in resolveReport; this strict convenience never hides failure as absence. */
    suspend fun resolve(name: IpnsName, now: Instant): IpnsRecord? {
        val report = resolveReport(name, now)
        if (report.quorumReached) return report.selected
        if (report.absent) return null
        throw IpnsDhtResolutionException(report)
    }

    suspend fun resolveReport(name: IpnsName, now: Instant): IpnsResolveReport = operation {
        val verifier = currentCoroutineContext()[IpnsCrypto]!!
        val valid = mutableListOf<IpnsDhtValue>()
        val missing = mutableListOf<Libp2pPeer>()
        val failures = mutableListOf<IpnsDhtFailure>()
        val lookup = walk(name.routingKey, IpnsDhtType.GET_VALUE) { reply ->
            if (reply.failure != null) failures += reply.failure
            else {
                val record = reply.message!!.record
                if (record == null) missing += reply.peer
                else try {
                    require(record.key.contentEquals(name.routingKey)) { "DHT record key mismatch" }
                    valid += IpnsDhtValue(reply.peer, IpnsRecord.verify(name, record.value, now, verifier))
                } catch (failure: IllegalArgumentException) {
                    failures += IpnsDhtFailure(reply.peer, IpnsDhtType.GET_VALUE, failure.message ?: "Invalid IPNS record")
                }
            }
            valid.size >= limits.minValidResponses
        }
        val selected = valid.maxWithOrNull { a, b -> a.record.compareTo(b.record) }?.record
        val repairs = mutableListOf<IpnsDhtReply>()
        if (limits.readRepair && selected != null && valid.size >= limits.minValidResponses) {
            val stale = mutableListOf<Libp2pPeer>()
            for (peer in lookup.closest.view) {
                val value = valid.singleOrNull { it.peer.id.contentEquals(peer.id) }
                // The IPFS correction rule covers every closest peer that did not return the winner,
                // including missing/invalid records and peers discovered in the final quorum round.
                if (value == null || value.record.compareTo(selected) < 0) stale += peer
            }
            val message = IpnsDhtMessage(IpnsDhtType.PUT_VALUE, name.routingKey, IpnsDhtRecord(name.routingKey, selected.bytes))
            for (reply in dispatch(stale.toSeries(), message).view) repairs += reply
        }
        IpnsResolveReport(lookup, selected, valid.toSeries(), missing.toSeries(), failures.toSeries(), limits.minValidResponses, repairs.toSeries())
    }

    internal suspend fun <T> operation(body: suspend () -> T): T = withContext(this + dialer + crypto) {
        withTimeout(limits.operationTimeoutMillis) { body() }
    }

    internal data class Candidate(val peer: Libp2pPeer, val distance: ByteArray)

    /** Iterative nearest-first traversal. A complete admitted round is collected before quorum/termination. */
    internal suspend fun walk(
        key: ByteArray,
        type: IpnsDhtType,
        observe: ((IpnsDhtReply) -> Boolean)? = null
    ): IpnsLookupReport {
        require(key.size in 1..512)
        val target = IpnsEncoding.sha256(key)
        val candidates = mutableMapOf<String, Candidate>()
        val queried = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        val replies = mutableListOf<IpnsDhtReply>()
        var candidateLimit = false
        fun add(peer: Libp2pPeer) {
            val permitted = IpnsDhtAddresses.filter(peer, limits.allowPrivateAddresses) ?: return
            val id = IpnsEncoding.base58(permitted.id)
            val known = candidates[id]
            if (known != null) {
                candidates[id] = known.copy(peer = IpnsDhtAddresses.merge(known.peer, permitted, limits.allowPrivateAddresses))
                return
            }
            val item = Candidate(permitted, distance(IpnsEncoding.sha256(permitted.id), target))
            if (candidates.size >= limits.maxCandidates) {
                candidateLimit = true
                val farthest = candidates.entries.maxWithOrNull { a, b -> compareDistance(a.value.distance, b.value.distance) }!!
                if (compareDistance(item.distance, farthest.value.distance) >= 0) return
                candidates.remove(farthest.key)
            }
            candidates[id] = item
        }
        for (peer in bootstrap.view) add(peer)
        fun closest() = candidates.entries.filter { it.key !in failed }
            .sortedWith { a, b -> compareDistance(a.value.distance, b.value.distance) }.take(limits.replication)
        var quorum = false
        while (!quorum && queried.size < limits.maxQueries) {
            val pending = closest().filter { it.key !in queried }.take(minOf(limits.parallelism, limits.maxQueries - queried.size))
            if (pending.isEmpty()) break
            for (entry in pending) queried += entry.key
            val round = dispatch(pending.map { it.value.peer }.toSeries(), IpnsDhtMessage(type, key))
            for (reply in round.view) {
                replies += reply
                if (reply.failure != null) failed += IpnsEncoding.base58(reply.peer.id)
                else for (peer in reply.message!!.closerPeers.view) add(peer)
                if (observe?.invoke(reply) == true) quorum = true
            }
        }
        val nearest = closest()
        val exhausted = nearest.none { it.key !in queried }
        return IpnsLookupReport(nearest.map { it.value.peer }.toSeries(), replies.toSeries(), exhausted,
            !exhausted && queried.size >= limits.maxQueries, candidateLimit)
    }

    /** Bounded fan-out/fan-in under a parented SupervisorJob; normal completion joins every admitted request. */
    internal suspend fun dispatch(peers: Series<Libp2pPeer>, message: IpnsDhtMessage): Series<IpnsDhtReply> {
        if (peers.size == 0) return emptyArray<IpnsDhtReply>().toSeries()
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val input = Channel<Libp2pPeer>(limits.parallelism)
        val output = Channel<IpnsDhtReply>(limits.parallelism)
        try {
            return withContext(owner) {
                coroutineScope {
                    val producer = launch {
                        try { for (peer in peers.view) input.send(peer) } finally { input.close() }
                    }
                    val workers = List(minOf(limits.parallelism, peers.size)) {
                        launch { for (peer in input) output.send(rpc(peer, message)) }
                    }
                    val results = mutableListOf<IpnsDhtReply>()
                    repeat(peers.size) { results += output.receive() }
                    producer.join()
                    workers.joinAll()
                    output.close()
                    results.toSeries()
                }
            }
        } finally {
            input.cancel()
            output.cancel()
            owner.complete()
            withContext(NonCancellable) { owner.join() }
        }
    }

    internal suspend fun rpc(peer: Libp2pPeer, request: IpnsDhtMessage): IpnsDhtReply = try {
        val response = withTimeout(limits.rpcTimeoutMillis) {
            val stream = currentCoroutineContext()[Libp2pDialer]!!.open(peer, IpnsDhtCodec.PROTOCOL)
            try {
                stream.write(IpnsDhtCodec.frame(request))
                val message = IpnsDhtCodec.receive(stream)
                require(message.type == request.type) { "DHT response type mismatch" }
                if (request.type == IpnsDhtType.PUT_VALUE) {
                    val echo = message.record
                    require(message.key.contentEquals(request.key) && echo != null && echo.key.contentEquals(request.key) &&
                        echo.value.contentEquals(request.record!!.value)) { "PUT_VALUE acknowledgement did not echo exact key/value" }
                }
                if (request.type == IpnsDhtType.GET_VALUE && message.key.isNotEmpty())
                    require(message.key.contentEquals(request.key)) { "GET_VALUE response key mismatch" }
                message
            } finally {
                withContext(NonCancellable) { withTimeout(limits.rpcTimeoutMillis) { stream.close() } }
            }
        }
        IpnsDhtReply(peer, response, null)
    } catch (failure: Exception) {
        if (failure is CancellationException) currentCoroutineContext().ensureActive()
        IpnsDhtReply(peer, null, IpnsDhtFailure(peer, request.type, "${failure::class.simpleName}: ${failure.message}"))
    }

}
