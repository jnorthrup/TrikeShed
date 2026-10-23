package borg.trikeshed.skinnerbox

import borg.trikeshed.htx.*
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.litebike.WireRawRoute
import borg.trikeshed.userspace.nio.platform.spi.platformGetRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

data class MeshExchangeLimits(
    val workers: Int = 3,
    val queueCapacity: Int = 16,
    val maxWaiters: Int = 32,
    val replayEntries: Int = 32,
    val rpcTimeoutMillis: Long = 15_000,
    val taskTimeoutMillis: Long = 10_000,
    val stroke: MeshStroke = MeshStroke(3, 256L * 1024),
    val maxAdmittedBytes: Long = 16L * 1024 * 1024,
    val controlWorkers: Int = 3,
    val controlQueueCapacity: Int = 8,
    val controlReplayEntries: Int = 16,
    val maxControlAdmittedBytes: Long = 4L * 1024 * 1024,
    val replayTombstones: Int = 4096,
    val controlReplayTombstones: Int = 4096,
) {
    init {
        require(workers in 1..3 && queueCapacity in 1..128 && maxWaiters in 1..128 && replayEntries in 1..128)
        require(replayTombstones in replayEntries..65_536 && controlReplayTombstones in controlReplayEntries..65_536)
        require(controlWorkers in 1..3 && controlQueueCapacity in 1..128 && controlReplayEntries in 1..128)
        require(maxControlAdmittedBytes in MeshEnvelope.MAX_WIRE_BYTES.toLong()..128L * 1024 * 1024)
        require(taskTimeoutMillis in 1..30_000 && rpcTimeoutMillis in taskTimeoutMillis..60_000)
        require(maxAdmittedBytes in MeshEnvelope.MAX_WIRE_BYTES.toLong()..128L * 1024 * 1024)
    }
}

class MeshOverloaded : IllegalStateException("Mesh admission capacity")
class MeshReplayUnavailable : IllegalStateException("Mesh nonce was consumed; cached reply is unavailable")

/**
 * Full-duplex bounded ownership. Ingress execution cannot be starved by outbound RPCs waiting for peers.
 * Accepted operations finish under this owner when a caller disconnects; drain closes admission and joins them.
 */
class MeshExchangeElement(
    val local: MeshPeer,
    private val identity: IpnsKeyPair,
    peers: Series<MeshPeer>,
    context: CoroutineContext,
    private val execute: suspend (MeshPeer, MeshTask) -> ByteArray,
    val limits: MeshExchangeLimits = MeshExchangeLimits(),
    private val crypto: IpnsCrypto = Ed25519,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<MeshExchangeElement> {
        private const val REPLAY_METADATA_BYTES = 256L
        private const val EXPIRY_STROKE = 64
    }
    override val key: CoroutineContext.Key<*> get() = Key
    private val registry: Map<String, MeshPeer> = buildMap {
        require(identity.publicKey.contentEquals(local.publicKey)) { "Mesh local identity mismatch" }
        require(peers.size <= 256) { "Mesh enrollment limit" }
        for (peer in peers.view) require(put(peer.id, peer) == null) { "Duplicate mesh enrollment" }
        val enrolled = get(local.id)
        require(enrolled == null || samePeer(enrolled, local)) { "Mesh local enrollment mismatch" }
        put(local.id, local)
    }
    val supervisor: CompletableJob = SupervisorJob(requireNotNull(context[Job]) { "Mesh requires an owning Job" })
    private val scope = CoroutineScope(context + supervisor + this)
    private val admission = Mutex()
    private val termination = Mutex()
    private var accepting = true
    private val replay = mutableMapOf<String, Replay>()
    private val replies = mutableMapOf<String, Reply>()
    private var replayJobs = 0
    private val progress = Channel<Unit>(Channel.CONFLATED)
    private val failure = CancellationException("Mesh owner stopped")

    private class Accepted(val owner: MeshExchangeElement) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Accepted>
    }
    private class Budget(val maximum: Long, val replayLimit: Int, val tombstoneLimit: Int) {
        var bytes = 0L
        var entries = 0
        var tombstones = 0
        val expiry = ArrayDeque<String>()
    }
    private val normalBudget = Budget(limits.maxAdmittedBytes, limits.replayEntries, limits.replayTombstones)
    private val controlBudget = Budget(limits.maxControlAdmittedBytes, limits.controlReplayEntries, limits.controlReplayTombstones)
    private class Flight(val requestBytes: Int, val reservation: Long, val budget: Budget, val action: suspend () -> ByteArray) {
        val result = CompletableDeferred<ByteArray>()
        var waiters = 0
        var released = false
        var finished = false
    }
    private class Replay(val request: ContentId, val expires: Long, val budget: Budget)
    private class Reply(val budget: Budget) {
        val result = CompletableDeferred<ByteArray>()
        var waiters = 0
        var cached = true
        var released = false
        var finished = false
    }
    private class Work(val key: String, val flight: Flight, val lane: Lane)
    private class Lane(val workerCount: Int, val capacity: Int, val budget: Budget, failure: CancellationException) {
        val flights = mutableMapOf<String, Flight>()
        val queue = Channel<Work>(capacity, onUndeliveredElement = { it.flight.result.completeExceptionally(failure) })
    }
    private val inbound = Lane(limits.workers, limits.queueCapacity, normalBudget, failure)
    private val outbound = Lane(limits.workers, limits.queueCapacity, normalBudget, failure)
    private val controlInbound = Lane(limits.controlWorkers, limits.controlQueueCapacity, controlBudget, failure)
    private val controlOutbound = Lane(limits.controlWorkers, limits.controlQueueCapacity, controlBudget, failure)
    private val lanes = listOf(inbound, outbound, controlInbound, controlOutbound)
    private val workers: List<Job>

    init {
        workers = lanes.flatMap { lane ->
            List(lane.workerCount) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var processed = 0
                    var strokeBytes = 0L
                    try {
                        for (work in lane.queue) {
                            // Reserve the full possible reply BEFORE the effect. A frame larger than
                            // the configured cache stroke takes an explicit indivisible-operation path.
                            val charge = work.flight.requestBytes.toLong() + MeshEnvelope.MAX_WIRE_BYTES
                            val oversized = charge > limits.stroke.maxBytes
                            try {
                                if (oversized || !limits.stroke.admits(processed, strokeBytes, charge)) {
                                    yield(); processed = 0; strokeBytes = 0
                                }
                                val result = withContext(Accepted(this@MeshExchangeElement)) { work.flight.action() }
                                require(result.size <= MeshEnvelope.MAX_WIRE_BYTES) { "Mesh reply limit" }
                                work.flight.result.complete(result.copyOf())
                            } catch (caught: Throwable) {
                                work.flight.result.completeExceptionally(caught)
                                if (caught is CancellationException) currentCoroutineContext().ensureActive()
                            } finally {
                                withContext(NonCancellable) { admission.withLock {
                                    if (lane.flights[work.key] === work.flight) lane.flights.remove(work.key)
                                    work.flight.finished = true
                                    release(work.flight)
                                    progress.trySend(Unit)
                                } }
                            }
                            processed++
                            strokeBytes += charge
                            if (oversized || processed >= limits.stroke.maxRequests || strokeBytes >= limits.stroke.maxBytes) {
                                processed = 0; strokeBytes = 0; yield()
                            }
                        }
                    } finally {
                        if (!supervisor.isActive) {
                            lanes.forEach { it.queue.cancel(failure) }
                            progress.trySend(Unit)
                        }
                    }
                }
            }
        }
    }

    private fun control(task: MeshTask): Boolean = task.verb == "mesh.retain" || task.verb == "mesh.find"

    private fun samePeer(left: MeshPeer, right: MeshPeer): Boolean =
        left.id == right.id && left.origin == right.origin && left.failureDomain == right.failureDomain &&
            left.subnet.toString() == right.subnet.toString()

    private fun enrolled(peer: MeshPeer): MeshPeer = requireNotNull(registry[peer.id]) { "Unknown mesh peer" }.also {
        require(samePeer(it, peer)) { "Mesh enrollment changed" }
    }

    private suspend fun run(
        key: String, requestBytes: Int, lane: Lane, action: suspend () -> ByteArray,
    ): ByteArray {
        currentCoroutineContext().ensureActive()
        val owned = currentCoroutineContext()[Accepted]?.owner === this
        val flight = admission.withLock {
            check(supervisor.isActive && (accepting || owned)) { "Mesh is draining" }
            val existing = lane.flights[key]
            if (existing != null) {
                if (existing.waiters >= limits.maxWaiters) throw MeshOverloaded()
                existing.waiters++
                existing
            } else {
                if (lane.flights.size >= lane.capacity + lane.workerCount) throw MeshOverloaded()
                val reservation = requestBytes.toLong() + 3L * MeshEnvelope.MAX_WIRE_BYTES
                val budget = lane.budget
                makeRoom(budget, reservation)
                if (reservation > budget.maximum - budget.bytes) throw MeshOverloaded()
                val created = Flight(requestBytes, reservation, budget, action).also { it.waiters = 1 }
                lane.flights[key] = created
                if (!lane.queue.trySend(Work(key, created, lane)).isSuccess) {
                    lane.flights.remove(key)
                    throw MeshOverloaded()
                }
                budget.bytes += reservation
                created
            }
        }
        try { return flight.result.await().copyOf() }
        finally { withContext(NonCancellable) { admission.withLock { flight.waiters--; release(flight) } } }
    }

    /** Called only under admission; the last waiter releases a completed flight's result reservation. */
    private fun release(flight: Flight) {
        if (!flight.released && flight.finished && flight.waiters == 0 && flight.result.isCompleted) {
            flight.released = true
            flight.budget.bytes -= flight.reservation
        }
    }

    private fun releaseReply(reply: Reply) {
        if (!reply.cached && !reply.released && reply.finished && reply.waiters == 0 && reply.result.isCompleted) {
            reply.released = true
            reply.budget.bytes -= MeshEnvelope.MAX_WIRE_BYTES
        }
    }

    /** Bounded full-response cache; compact nonce records remain until their validity window expires. */
    private fun makeRoom(budget: Budget, bytes: Long, replySlot: Boolean = false) {
        val iterator = replies.iterator()
        while ((bytes > budget.maximum - budget.bytes || (replySlot && budget.entries >= budget.replayLimit)) && iterator.hasNext()) {
            val reply = iterator.next().value
            if (reply.budget === budget && reply.finished && reply.waiters == 0) {
                iterator.remove()
                reply.cached = false
                budget.entries--
                releaseReply(reply)
            }
        }
    }

    /** At most 64 compact records are inspected per admission, independent of enrollment traffic. */
    private fun expire(budget: Budget, now: Long) {
        repeat(minOf(EXPIRY_STROKE, budget.expiry.size)) {
            val key = budget.expiry.removeFirst()
            val entry = checkNotNull(replay[key])
            val reply = replies[key]
            if (entry.expires < now && (reply == null || (reply.finished && reply.waiters == 0))) {
                replay.remove(key)
                budget.tombstones--
                budget.bytes -= REPLAY_METADATA_BYTES
                if (reply != null) {
                    replies.remove(key)
                    reply.cached = false
                    budget.entries--
                    releaseReply(reply)
                }
            } else budget.expiry.addLast(key)
        }
    }

    suspend fun dispatch(target: MeshPeer, task: MeshTask): ByteArray = dispatchReply(target, task).payload

    /** The signed bytes remain available for a domain-specific custody verifier. */
    suspend fun dispatchReply(target: MeshPeer, task: MeshTask): MeshReply {
        val peer = enrolled(target)
        suspend fun exchange(): ByteArray {
            val nonce = ByteArray(32).also { platformGetRandom(it) }
            val request = MeshEnvelope.request(local, peer, task, identity, nowMillis(), nonce, crypto)
            val responseBytes = if (peer.id == local.id) receive(request.bytes()) else {
                val htx = checkNotNull(currentCoroutineContext()[HtxKey]) { "Mesh requires userspace HTX" }
                val response = htx.request(parseHtxRequest(peer.origin + MeshEnvelope.PATH,
                    method = HtxMethod.POST, body = ByteSeries(request.bytes())).copy(
                    timeoutMs = limits.rpcTimeoutMillis,
                    maxResponseBytes = MeshEnvelope.MAX_WIRE_BYTES + 4096,
                    headers = htxHeaders("Content-Type" j "application/cbor"),
                ))
                // No redirect replay, no automatic request retry, and no success inference from a transport failure.
                check(response.status == 200) { "Mesh remote HTTP status ${response.status}" }
                response.body.toArray()
            }
            val response = MeshEnvelope.decode(responseBytes)
            response.verifyResponse(request, peer, nowMillis(), crypto)
            return responseBytes
        }
        // Local work enters ingress directly; consuming an egress worker while waiting on local
        // execution would couple both lanes during shutdown and nested local calls.
        val wire = if (peer.id == local.id) exchange()
            else run(peer.id + ":" + task.id.value, MeshEnvelope.MAX_WIRE_BYTES,
                if (control(task)) controlOutbound else outbound) { exchange() }
        val response = MeshEnvelope.decode(wire)
        return MeshReply(peer, task.id, response.payload, wire)
    }

    suspend fun receive(bytes: ByteArray): ByteArray {
        currentCoroutineContext().ensureActive()
        require(bytes.size in 1..MeshEnvelope.MAX_WIRE_BYTES) { "Mesh ingress limit" }
        val snapshot = bytes.copyOf()
        val request = MeshEnvelope.decode(snapshot)
        val peer = requireNotNull(registry[request.source]) { "Unknown mesh peer" }
        val now = nowMillis()
        val task = request.verifyRequest(peer, local, now, crypto)
        val nonce = request.nonce.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val replayKey = peer.id + ":" + nonce
        val requestId = ContentId.of(snapshot)
        var fresh = false
        val owned = currentCoroutineContext()[Accepted]?.owner === this
        val lane = if (control(task)) controlInbound else inbound
        val entry = admission.withLock {
            check(supervisor.isActive && (accepting || owned)) { "Mesh is draining" }
            val budget = lane.budget
            expire(budget, now)
            val existing = replay[replayKey]
            if (existing != null) {
                require(existing.request == requestId) { "Mesh nonce reused for another request" }
                val reply = replies[replayKey] ?: throw MeshReplayUnavailable()
                if (reply.waiters >= limits.maxWaiters) throw MeshOverloaded()
                reply.waiters++
                reply
            } else {
                if (budget.tombstones >= budget.tombstoneLimit) throw MeshOverloaded()
                val reservation = MeshEnvelope.MAX_WIRE_BYTES + REPLAY_METADATA_BYTES
                makeRoom(budget, reservation, replySlot = true)
                if (budget.entries >= budget.replayLimit || reservation > budget.maximum - budget.bytes) throw MeshOverloaded()
                Reply(budget).also {
                    it.waiters = 1
                    replay[replayKey] = Replay(requestId, request.timeMillis + MeshEnvelope.CLOCK_SKEW_MILLIS, budget)
                    replies[replayKey] = it
                    budget.expiry.addLast(replayKey)
                    fresh = true
                    budget.bytes += reservation
                    budget.entries++
                    budget.tombstones++
                    replayJobs++
                }
            }
        }
        if (fresh) {
            // The replay response belongs to the owner, not to the HTTP caller that may disconnect.
            scope.launch(Accepted(this), start = CoroutineStart.UNDISPATCHED) {
                try {
                    val body = run(peer.id + ":" + task.id.value, snapshot.size, lane) {
                        withTimeout(limits.taskTimeoutMillis) { execute(peer, task) }.also {
                            require(it.size <= MeshTask.MAX_PAYLOAD_BYTES) { "Mesh handler reply limit" }
                        }
                    }
                    entry.result.complete(MeshEnvelope.response(request, body, identity, nowMillis(), crypto).bytes())
                } catch (caught: Throwable) {
                    entry.result.completeExceptionally(caught)
                } finally {
                    withContext(NonCancellable) { admission.withLock {
                        entry.finished = true
                        releaseReply(entry)
                        replayJobs--
                        progress.trySend(Unit)
                    } }
                }
            }.invokeOnCompletion { cause -> if (cause != null) entry.result.completeExceptionally(cause) }
        }
        try { return entry.result.await().copyOf() }
        finally { withContext(NonCancellable) { admission.withLock { entry.waiters--; releaseReply(entry) } } }
    }

    fun route(): WireRawRoute = { method, path, bytes, _ ->
        when {
            path != MeshEnvelope.PATH -> null
            method != "POST" -> WireHttpResponse(405, "", headers = mapOf("Allow" to "POST"))
            bytes.size > MeshEnvelope.MAX_WIRE_BYTES -> WireHttpResponse(413, "")
            else -> try { WireHttpResponse(200, "", "application/cbor", receive(bytes)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: MeshOverloaded) { WireHttpResponse(429, "") }
            catch (_: MeshReplayUnavailable) { WireHttpResponse(409, "") }
            catch (_: IllegalArgumentException) { WireHttpResponse(400, "") }
            catch (_: IllegalStateException) { WireHttpResponse(503, "") }
        }
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        termination.withLock {
            admission.withLock { accepting = false }
            // Keep every lane open until admitted handlers have finished their nested effects.
            // The private Accepted context grants no new external caller admission during drain.
            while (admission.withLock {
                supervisor.isActive && (replayJobs != 0 || lanes.any { it.flights.isNotEmpty() })
            }) progress.receive()
            lanes.forEach { it.queue.close() }
            workers.joinAll()
            supervisor.complete()
            supervisor.join()
            admission.withLock {
                replay.values.forEach { it.budget.tombstones--; it.budget.bytes -= REPLAY_METADATA_BYTES }
                replay.clear()
                normalBudget.expiry.clear(); controlBudget.expiry.clear()
                replies.values.forEach {
                    it.cached = false
                    it.finished = true
                    it.result.completeExceptionally(failure)
                    it.budget.entries--
                    releaseReply(it)
                }
                replies.clear()
                lanes.forEach { lane ->
                    lane.flights.values.forEach { it.result.completeExceptionally(failure); it.finished = true; release(it) }
                    lane.flights.clear()
                }
            }
        }
    }

    suspend fun close() = drain()
}
