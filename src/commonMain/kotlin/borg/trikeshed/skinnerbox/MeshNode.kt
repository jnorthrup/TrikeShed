package borg.trikeshed.skinnerbox

import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * Namespace ownership, one execution, and three durable custodians. All peers run this same owner.
 * The membership epoch is configured explicitly; this is not leader election or a consensus protocol.
 */
class MeshNode(
    val local: MeshPeer,
    private val identity: IpnsKeyPair,
    peers: Series<MeshPeer>,
    cells: Series<MeshCell>,
    val persistence: MeshPersistence,
    context: CoroutineContext,
    private val application: suspend (String, MeshTask) -> ByteArray = persistence::execute,
    exchangeLimits: MeshExchangeLimits = MeshExchangeLimits(),
    private val crypto: IpnsCrypto = Ed25519,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<MeshNode> {
        val mutations = setOf("row.put", "row.delete", "object.put", "object.delete", "twin.merge")
        val reads = setOf("row.get", "row.list", "object.get", "object.list", "changes", "event.get",
            "twin.get", "twin.residual", "twin.references", "twin.project", "mesh.lookup")
        val reservedCollections = setOf("twin-batches", "mesh-decisions")
    }
    override val key: CoroutineContext.Key<*> get() = Key
    private val cellByNamespace = buildMap {
        require(cells.size in 1..64) { "Mesh cell registry" }
        for (cell in cells.view) require(put(cell.namespace, cell) == null) { "Duplicate mesh namespace" }
    }
    private val enrolled = buildMap {
        require(peers.size <= MeshRouting.MAX_PEERS) { "Mesh peer registry" }
        for (peer in peers.view) require(put(peer.id, peer) == null) { "Duplicate mesh enrollment" }
        val previous = get(local.id)
        require(previous == null || samePeer(previous, local)) { "Mesh local enrollment differs" }
        put(local.id, local)
        require(size <= MeshRouting.MAX_PEERS) { "Mesh peer registry" }
    }
    private val supervisor: CompletableJob
    private val scope: CoroutineScope
    private val protocolBudgetMillis = minOf(4_000L, maxOf(1L, exchangeLimits.taskTimeoutMillis / 2))
    private val mutationOwners = cellByNamespace.mapValues { Mutex() }
    private val close = Mutex()
    private var closed = false
    private class Call(val task: MeshTask) { val result = CompletableDeferred<ByteArray>() }
    private val calls = Channel<Call>(16, onUndeliveredElement = {
        it.result.completeExceptionally(CancellationException("Mesh node stopped"))
    })

    val exchange: MeshExchangeElement
    private val worker: Job

    init {
        require(identity.publicKey.contentEquals(local.publicKey)) { "Mesh local identity differs" }
        require(persistence.durability == StorageDurability.DURABLE) { "N3 custody requires durable persistence" }
        for (cell in cellByNamespace.values) for (peer in cell.members.view) {
            val known = requireNotNull(enrolled[peer.id]) { "Cell member is not enrolled" }
            require(known.origin == peer.origin && known.failureDomain == peer.failureDomain &&
                known.subnet.toString() == peer.subnet.toString()) { "Cell enrollment differs" }
        }
        val parent = requireNotNull(context[Job]) { "Mesh node requires an owning Job" }
        parent.ensureActive()
        supervisor = SupervisorJob(parent)
        scope = CoroutineScope(context + supervisor + this)
        exchange = try {
            MeshExchangeElement(local, identity, peers, scope.coroutineContext, ::receive,
                exchangeLimits, crypto, nowMillis)
        } catch (failure: Throwable) {
            supervisor.cancel()
            throw failure
        }
        worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (call in calls) {
                    try { call.result.complete(dispatch(call.task)) }
                    catch (failure: Throwable) {
                        call.result.completeExceptionally(failure)
                        if (failure is CancellationException) currentCoroutineContext().ensureActive()
                    }
                    yield()
                }
            } finally {
                calls.close()
                while (true) {
                    val pending = calls.tryReceive().getOrNull() ?: break
                    pending.result.completeExceptionally(CancellationException("Mesh node stopped"))
                }
            }
        }
    }

    /** Bounded public ingress. Caller cancellation does not undo an admitted mutation. */
    suspend fun submit(task: MeshTask): ByteArray {
        currentCoroutineContext().ensureActive()
        val call = Call(task)
        if (!calls.trySend(call).isSuccess) throw MeshOverloaded()
        return call.result.await().copyOf()
    }

    fun cell(namespace: String): MeshCell = requireNotNull(cellByNamespace[namespace]) { "Unknown mesh namespace" }

    private suspend fun dispatch(task: MeshTask): ByteArray {
        val cell = cell(task.namespace)
        require(task.verb in mutations || task.verb in reads) { "Unsupported mesh operation" }
        if (task.verb == "mesh.lookup") return lookup(cell, task)
        val wire = MeshTask(task.namespace, "mesh.execute", CanonicalCbor.encodeMap(mapOf(
            "epoch" to cell.epoch.value, "verb" to task.verb, "payload" to task.payload)))
        val encoded = if (cell.owner.id == local.id) receive(local, wire) else exchange.dispatch(cell.owner, wire)
        val envelope = MeshPersistenceCodec.decode(encoded)
        if (envelope["status"] == "prior-custody-pending") {
            require(envelope.keys == setOf("status", "requestId", "record", "holders")) { "Prior custody fields" }
            val prior = envelope["requestId"] as? String ?: error("Prior request identity")
            val record = ContentId(envelope["record"] as? String ?: error("Prior record identity"))
            val holders = envelope["holders"] as? Long ?: error("Prior custody count")
            require(holders in 0..3) { "Prior custody count" }
            throw MeshPriorCustodyPending(prior, record, holders.toInt())
        }
        if (envelope["status"] == "error") {
            require(envelope.keys == setOf("status", "reason")) { "Mesh error fields" }
            val reason = MeshPersistenceError.entries.firstOrNull { it.name == envelope["reason"] }
                ?: error("Mesh error reason")
            throw MeshPersistenceException(reason, "Namespace owner refused the operation: ${reason.name}")
        }
        require(envelope.keys == setOf("status", "value") && envelope["status"] == "ok") { "Mesh result fields" }
        val reply = envelope["value"] as? ByteArray ?: error("Mesh result bytes")
        if (task.verb !in mutations) return reply
        val result = MeshPersistenceCodec.decode(reply)
        require(result.keys == setOf("epoch", "task", "record", "result", "custody", "acknowledged") &&
            result["epoch"] == cell.epoch.value && result["task"] == task.id.value) { "Mesh execution binding" }
        val record = ContentId(result["record"] as? String ?: error("Mesh record identity"))
        val body = result["result"] as? ByteArray ?: error("Mesh result")
        val event = MeshPersistenceCodec.decode(body)["event"] as? ByteArray ?: error("Mesh retained result event")
        require(ContentId.of(retentionPacket(cell, event)) == record) { "Mesh result custody binding" }
        val proofs = result["custody"] as? List<*> ?: error("Mesh custody")
        require(proofs.size <= 3) { "Mesh custody count" }
        val holders = proofs.map { MeshCustody.verify(it as? ByteArray ?: error("Custody bytes"), cell, record, nowMillis(), crypto).peer }.toSet()
        require(holders.size == proofs.size) { "Duplicate mesh custody" }
        val acknowledged = result["acknowledged"] as? Boolean ?: error("Mesh custody completion")
        if (holders.size != 3 || !acknowledged) throw MeshCustodyPending(cell.namespace, cell.epoch, record, body, holders.size)
        return body
    }

    private suspend fun receive(sender: MeshPeer, task: MeshTask): ByteArray {
        val cell = cell(task.namespace)
        val body = MeshPersistenceCodec.decode(task.payload)
        require(body["epoch"] == cell.epoch.value) { "Mesh membership epoch differs; explicit handoff required" }
        return when (task.verb) {
            "mesh.execute" -> {
                require(local.id == cell.owner.id) { "This member is not the namespace executor" }
                require(body.keys == setOf("epoch", "verb", "payload")) { "Mesh execution fields" }
                val operation = MeshTask(task.namespace, body["verb"] as? String ?: error("Mesh verb"),
                    body["payload"] as? ByteArray ?: error("Mesh payload"))
                require(operation.verb in mutations || operation.verb in reads) { "Unsupported mesh operation" }
                if (operation.verb.startsWith("row.") || operation.verb.startsWith("object.")) {
                    val fields = MeshPersistenceCodec.decode(operation.payload)
                    require(fields["collection"] !in reservedCollections) { "Reserved mesh collection" }
                }
                try {
                    persistence.authorize(sender.id, cell.namespace)
                    bind(cell)
                    val value = if (operation.verb in mutations) mutationOwners.getValue(cell.namespace).withLock {
                        val previous = repairPending(cell, sender, operation)
                        val result = application(sender.id, operation)
                        val receipt = MeshPersistenceCodec.decode(result)
                        require(receipt["durable"] == true) { "Application publication is not durable" }
                        val event = receipt["event"] as? ByteArray ?: error("Mutation has no retained event")
                        require(previous == null || previous.event.contentEquals(event)) { "Retry event differs" }
                        val retained = previous ?: retainEvent(cell, event)
                        val acknowledged = retained.proofs.size == 3 && acknowledgeIfPossible(cell, retained.event)
                        mutationReply(cell, operation, result, retained, acknowledged)
                    } else if (operation.verb == "mesh.lookup") lookup(cell, operation)
                        else application(sender.id, operation)
                    MeshPersistenceCodec.encode(mapOf("status" to "ok", "value" to value))
                } catch (failure: MeshPriorCustodyPending) {
                    MeshPersistenceCodec.encode(mapOf("status" to "prior-custody-pending",
                        "requestId" to failure.priorRequestId, "record" to failure.record.value,
                        "holders" to failure.holders.toLong()))
                } catch (failure: MeshPersistenceException) {
                    MeshPersistenceCodec.encode(mapOf("status" to "error", "reason" to failure.reason.name))
                }
            }
            "mesh.find" -> {
                require(body.keys == setOf("epoch", "key")) { "Mesh lookup fields" }
                bind(cell)
                val key = ContentId(body["key"] as? String ?: error("Mesh lookup key"))
                MeshPersistenceCodec.encode(mapOf("epoch" to cell.epoch.value, "key" to key.value,
                    "peers" to closest(key, task.namespace).view.map { it.id }))
            }
            "mesh.retain" -> {
                require(sender.id == cell.owner.id && cell.member(local.id) != null) { "Custody sender/member" }
                require(body.keys == setOf("epoch", "packet")) { "Custody packet fields" }
                hold(cell, body["packet"] as? ByteArray ?: error("Custody packet")).bytes()
            }
            else -> error("Unknown mesh protocol operation")
        }
    }

    private class Retention(val event: ByteArray, val record: ContentId, val proofs: List<ByteArray>)

    private fun retentionPacket(cell: MeshCell, event: ByteArray): ByteArray {
        val stored = MeshPersistenceCodec.decode(event, MeshPersistenceCodec.PACKET_BYTES)
        require(stored["format"] == MeshPersistence.EVENT_FORMAT && stored["tenant"] == cell.namespace) {
            "Mutation event binding"
        }
        // Both values are durable event fields: another gateway and a restarted primary reconstruct identical bytes.
        val actor = stored["peer"] as? String ?: error("Mutation event author")
        val digest = ContentId(stored["requestDigest"] as? String ?: error("Mutation request digest"))
        return CanonicalCbor.encodeMap(mapOf("format" to "skinnerbox.retention/v1", "epoch" to cell.epoch.value,
            "namespace" to cell.namespace, "actor" to actor, "task" to digest.value, "event" to event)).also {
            require(it.size <= MeshPersistenceCodec.PACKET_BYTES) { "Custody packet bound" }
        }
    }

    private fun mutationReply(cell: MeshCell, task: MeshTask, result: ByteArray, retained: Retention,
        acknowledged: Boolean): ByteArray {
        require(MeshPersistenceCodec.decode(result)["durable"] == true) { "Application publication is not durable" }
        return MeshPersistenceCodec.encode(mapOf("epoch" to cell.epoch.value, "task" to task.id.value,
            "record" to retained.record.value, "result" to result, "custody" to retained.proofs,
            "acknowledged" to acknowledged))
    }

    private suspend fun retainEvent(cell: MeshCell, event: ByteArray): Retention {
        val packet = retentionPacket(cell, event)
        val record = ContentId.of(packet)
        val proofs = mutableListOf<ByteArray>()
        try { proofs.add(hold(cell, packet).bytes()) }
        catch (failure: MeshPersistenceException) {
            // The event already committed. A capacity/IO failure in its local retention is partial custody,
            // never a precommit refusal that would encourage the caller to invent another request ID.
        }
        val retention = MeshTask(cell.namespace, "mesh.retain",
            CanonicalCbor.encodeMap(mapOf("epoch" to cell.epoch.value, "packet" to packet)))
        coroutineScope {
            val completions = Channel<ByteArray?>(2)
            for (peer in s_[cell.replicas.a, cell.replicas.b].view) launch {
                completions.send(retainRemote(peer, cell, record, retention))
            }
            repeat(2) { completions.receive()?.let(proofs::add) }
            completions.close()
        }
        return Retention(event, record, proofs)
    }

    /** Oldest pending events are repaired before any new mutation can extend the namespace history. */
    private suspend fun repairPending(cell: MeshCell, sender: MeshPeer, task: MeshTask): Retention? {
        repeat(4) { index ->
            val pending = MeshPersistenceCodec.decode(persistence.execute(local.id,
                MeshTask(cell.namespace, "custody.pending", CanonicalCbor.encodeMap(emptyMap()))))
            require(pending.keys == setOf("events", "more") && pending["more"] is Boolean) { "Pending custody fields" }
            val events = pending["events"] as? List<*> ?: error("Pending custody events")
            require(events.size <= 1) { "Pending custody bound" }
            if (events.isEmpty()) { require(pending["more"] == false); return null }
            val event = events.single() as? ByteArray ?: error("Pending custody event")
            val fields = MeshPersistenceCodec.decode(event, MeshPersistenceCodec.PACKET_BYTES)
            val request = fields["requestId"] as? String ?: error("Pending request identity")
            if (index == 3) throw MeshPriorCustodyPending(request, ContentId.of(retentionPacket(cell, event)), 0)
            val retained = retainEvent(cell, event)
            if (retained.proofs.size != 3 || !acknowledgeIfPossible(cell, event)) {
                // Returning the original result is permitted only for an exact retry, after current application grants.
                if (sameMutation(sender, task, fields)) return retained
                throw MeshPriorCustodyPending(request, retained.record, retained.proofs.size)
            }
            yield()
        }
        error("Pending custody stroke")
    }

    private fun sameMutation(sender: MeshPeer, task: MeshTask, event: Map<String, Any?>): Boolean {
        if (task.verb == "twin.merge") {
            if (event["kind"] != "object" || event["collection"] != MeshTwinElement.COLLECTION ||
                event["peer"] != sender.id) return false
            val batch = MeshPersistenceCodec.decode(event["value"] as? ByteArray ?: return false)
            return batch["namespace"] == task.namespace && batch["author"] == sender.id && batch["task"] == task.id.value
        }
        val digest = ContentId.of(CanonicalCbor.encodeMap(mapOf("tenant" to task.namespace,
            "verb" to task.verb, "payload" to task.payload)))
        return event["requestDigest"] == digest.value
    }

    private suspend fun acknowledgeIfPossible(cell: MeshCell, event: ByteArray): Boolean = try {
        acknowledge(cell, event)
        true
    } catch (_: MeshPersistenceException) {
        // Three proofs do not imply that the owner's durable pending-outbox ACK was published.
        false
    }

    private suspend fun acknowledge(cell: MeshCell, event: ByteArray) {
        val revision = ContentId.of(event)
        val result = MeshPersistenceCodec.decode(persistence.execute(local.id, MeshTask(cell.namespace, "custody.ack",
            CanonicalCbor.encodeMap(mapOf("revision" to revision.value, "epoch" to cell.epoch.value)))))
        require(result["revision"] == revision.value && result["durable"] == true) { "Custody ACK is not durable" }
    }

    private suspend fun retainLocal(cell: MeshCell, bytes: ByteArray) {
        bind(cell)
        val cid = ContentId.of(bytes)
        val reply = MeshPersistenceCodec.decode(persistence.execute(local.id, MeshTask(cell.namespace, "replica.retain",
            CanonicalCbor.encodeMap(mapOf("contentId" to cid.value, "value" to bytes)))))
        require(reply["durable"] == true && reply["contentId"] == cid.value) { "Custody publication not durable" }
    }

    private suspend fun hold(cell: MeshCell, packet: ByteArray): MeshCustody {
        val fields = MeshPersistenceCodec.decode(packet, MeshPersistenceCodec.PACKET_BYTES)
        require(fields.keys == setOf("format", "epoch", "namespace", "actor", "task", "event") &&
            fields["format"] == "skinnerbox.retention/v1" && fields["epoch"] == cell.epoch.value &&
            fields["namespace"] == cell.namespace) { "Retention binding" }
        ContentId(fields["task"] as? String ?: error("Retained task identity"))
        require(fields["actor"] is String && fields["event"] is ByteArray)
        val event = MeshPersistenceCodec.decode(fields["event"] as ByteArray, MeshPersistenceCodec.PACKET_BYTES)
        require(event["format"] == MeshPersistence.EVENT_FORMAT && event["tenant"] == cell.namespace &&
            event["peer"] == fields["actor"] && event["requestDigest"] == fields["task"]) { "Retained event binding" }
        retainLocal(cell, cell.bytes())
        retainLocal(cell, packet)
        return MeshCustody.issue(local, identity, cell, ContentId.of(packet), nowMillis(), crypto)
    }

    private fun samePeer(left: MeshPeer, right: MeshPeer): Boolean =
        left.id == right.id && left.origin == right.origin && left.failureDomain == right.failureDomain &&
            left.subnet.toString() == right.subnet.toString()

    private suspend fun bind(cell: MeshCell) {
        val bound = MeshPersistenceCodec.decode(persistence.execute(local.id, MeshTask(cell.namespace, "cell.bind",
            CanonicalCbor.encodeMap(mapOf("epoch" to cell.epoch.value)))))
        require(bound.keys == setOf("epoch", "durable") && bound["epoch"] == cell.epoch.value && bound["durable"] == true) {
            "Cell binding is not durable"
        }
    }

    private fun closest(key: ContentId, namespace: String): Series<MeshPeer> {
        val peers = enrolled.values.toTypedArray()
        return MeshRouting.closest(key, meshSubnet(namespace), local.subnet, peers.size j { peers[it] })
    }

    /** Registered identities alone may be returned or contacted; remote answers never enroll origins. */
    private suspend fun lookup(cell: MeshCell, task: MeshTask): ByteArray {
        bind(cell)
        val body = MeshPersistenceCodec.decode(task.payload)
        require(body.keys == setOf("key")) { "Mesh lookup fields" }
        val key = ContentId(body["key"] as? String ?: error("Mesh lookup key"))
        val candidates = closest(key, cell.namespace).view.associateBy { it.id }.toMutableMap()
        val scopeBudget = candidates.size
        val visited = mutableSetOf(local.id)
        val responded = mutableSetOf(local.id)
        val query = MeshTask(cell.namespace, "mesh.find", CanonicalCbor.encodeMap(mapOf(
            "epoch" to cell.epoch.value, "key" to key.value)))
        withTimeoutOrNull(protocolBudgetMillis) {
            repeat(3) {
                val next = candidates.values.filter { it.id !in visited }
                    .sortedWith { a, b -> MeshRouting.compareDistance(key, a.id, b.id) }.take(3)
                if (next.isEmpty()) return@withTimeoutOrNull
                visited.addAll(next.map { it.id })
                val answers = coroutineScope {
                    val completions = Channel<Pair<String, List<String>>?>(3)
                    for (peer in next) launch {
                        val answer = try {
                            withTimeout(maxOf(1L, protocolBudgetMillis / 3)) {
                                val fields = MeshPersistenceCodec.decode(exchange.dispatch(peer, query))
                                require(fields.keys == setOf("epoch", "key", "peers") &&
                                    fields["epoch"] == cell.epoch.value && fields["key"] == key.value) { "Mesh lookup binding" }
                                val ids = fields["peers"] as? List<*> ?: error("Mesh lookup peers")
                                require(ids.size <= MeshRouting.MAX_CANDIDATES) { "Mesh lookup peer bound" }
                                peer.id to ids.map { value ->
                                    (value as? String ?: error("Mesh lookup peer identity")).also(MeshRouting::requireId)
                                }
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) currentCoroutineContext().ensureActive()
                            null
                        }
                        completions.send(answer)
                    }
                    val result = mutableListOf<Pair<String, List<String>>>()
                    repeat(next.size) { completions.receive()?.let(result::add) }
                    completions.close()
                    result
                }
                for ((responder, ids) in answers) {
                    responded.add(responder)
                    for (id in ids) {
                        val peer = enrolled[id] ?: continue
                        if (MeshRouting.contains(local.subnet, peer.subnet) &&
                            MeshRouting.contains(peer.subnet, meshSubnet(cell.namespace))) candidates[id] = peer
                    }
                }
                val nearest = candidates.values.sortedWith { a, b -> MeshRouting.compareDistance(key, a.id, b.id) }
                    .take(scopeBudget)
                candidates.clear()
                for (peer in nearest) candidates[peer.id] = peer
            }
        }
        val found = candidates.values.filter { it.id in responded }
            .sortedWith { a, b -> MeshRouting.compareDistance(key, a.id, b.id) }
        return MeshPersistenceCodec.encode(mapOf("epoch" to cell.epoch.value, "key" to key.value,
            "peers" to found.map { it.id }))
    }

    /** Three bounded attempts reuse exactly the same content-addressed retention packet. */
    private suspend fun retainRemote(peer: MeshPeer, cell: MeshCell, record: ContentId, task: MeshTask): ByteArray? =
        withTimeoutOrNull(protocolBudgetMillis) {
            repeat(3) { attempt ->
                try {
                    val wire = withTimeout(maxOf(1L, protocolBudgetMillis / 3)) { exchange.dispatch(peer, task) }
                    val proof = MeshCustody.verify(wire, cell, record, nowMillis(), crypto)
                    require(proof.peer == peer.id) { "Custody target differs" }
                    return@withTimeoutOrNull proof.bytes()
                } catch (failure: Exception) {
                    if (failure is CancellationException) currentCoroutineContext().ensureActive()
                }
                if (attempt < 2) delay(meshRetryDelay(peer.id, record, attempt, 25, 150))
            }
            null
        }

    /** Listener admission must stop first; drain this owner before draining application/persistence owners. */
    suspend fun drain() = withContext(NonCancellable) {
        close.withLock {
            if (!closed) {
                calls.close()
                worker.join()
                exchange.drain()
                supervisor.complete()
                supervisor.join()
                closed = true
            }
        }
    }
}

/** The caller's new mutation was not admitted; an older durable event still needs custody repair. */
class MeshPriorCustodyPending(val priorRequestId: String, val record: ContentId, val holders: Int) :
    IllegalStateException("Previous request $priorRequestId has $holders of 3 custody proofs and pending completion; this mutation was not admitted")
