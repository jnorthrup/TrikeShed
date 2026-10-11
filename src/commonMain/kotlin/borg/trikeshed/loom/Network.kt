@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.context.*
import borg.trikeshed.htx.*
import borg.trikeshed.ipns.*
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.*
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.platform.HostSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.*
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** network.rs `type Replay`: (source, nonce as lowercase hex) to expiry, in BTreeMap order when persisted. */
typealias Replay = MutableMap<Pair<String, String>, ULong>

/** network.rs Reconciliation: an incomplete attempt proves only freshly checked local custody, not quorum. */
class Reconciliation(
    val complete: Boolean,
    val custody: Series<Custody>,
    /** Valid peer records refused for local capacity, not integrity faults. */
    val backpressured_records: Int,
)

/**
 * cocaine-rats crates/loom-mesh/src/network.rs Node over its [Store]: [handle] is the HTTP handler (statuses, decode
 * bounds, [authenticate] with the role gate and the persisted replay table, the signed `[code, payload]` response),
 * [operation] the route table, and [run] serves the node's own [listener] (fed one complete HTTP request at a time by
 * the platform bind adapter) beside the periodic reconciliation. Drain stops admission at the listener, answers what
 * was admitted, joins, then drains the peer client and closes the archive and the store.
 */
class Node(
    val config: Config,
    val signing_key: IpnsKeyPair,
    val store: Store,
    val replay: Replay,
    val client: Client,
    val gcs: GcsArchive?,
    val crypto: IpnsCrypto = Ed25519,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<Node>() {
        /** network.rs Node::open: the configured store, its replay table, the peer client and the GCS archive when configured. */
        suspend fun open(config: Config, key: ByteArray, crypto: IpnsCrypto = Ed25519): Node {
            if (HostSystem.getenv("K_SERVICE") != null) error("Cloud Run cannot host the local replica store; use --settle")
            if (config.runpod_serverless) error("serverless bootstrap required")
            config.gcs?.validate()
            return open_inner(config, key, null, crypto)
        }

        /** Explicit isolated HTTP integration seam, never a configurable production origin. */
        suspend fun open_with_gcs_for_local_test(config: Config, key: ByteArray, gcs: GcsArchive, crypto: IpnsCrypto = Ed25519): Node {
            if (!config.listen.is_loopback() || !config.allow_loopback_http || config.gcs == null || config.legacy_archive_test_only)
                error("GCS injection requires explicit loopback test configuration")
            return open_inner(config, key, gcs, crypto)
        }

        /** network.rs Node::open_serverless: the bootstrap's node, once its store is a direct child of the mounted RunPod volume. */
        suspend fun open_serverless(bootstrap: Bootstrap, crypto: IpnsCrypto = Ed25519): Node {
            mounted_store(bootstrap.config.store)
            return open_inner(bootstrap.config, bootstrap.key, null, crypto, bootstrap.tokens, bootstrap.gcs)
        }

        /**
         * [gcs] null opens the configured archive over the client's reactor: with [credentials] when the bootstrap
         * injected them, else leased when it names a service account. [tokens] are the client's static invocations.
         */
        suspend fun open_inner(
            config: Config, key: ByteArray, gcs: GcsArchive?, crypto: IpnsCrypto,
            tokens: Map<String, String> = emptyMap(), credentials: ServiceAccountKey? = null,
        ): Node {
            config.validate()
            val keys = IpnsKeyPair(Ed25519.publicKey(key), key)
            if (!keys.publicKey.contentEquals(config.member(config.id).key())) error("local key mismatch")
            val producers = config.members.view.toList().filter { it.has(Role.Producer) }.associate { it.id to it.key() }
            val store = Store.open_config(config, producers, crypto)
            try {
                val replay = load_replay(store, config)
                val client = Client(config.id, keys, config.allow_loopback_http, config.insecure_local_test_only, config.request_timeout_ms, crypto)
                client.invocation_tokens.store(static_invocations(tokens))
                client.open()
                try {
                    val archive = gcs ?: config.gcs?.let {
                        if (credentials != null) GcsArchive.build(it, GcsArchive.ORIGIN, GcsArchive.Auth.Google(credentials), client.reactor)
                        else if (it.service_account != null) GcsArchive.leased(it, client.reactor) else GcsArchive.new(it, client.reactor)
                    }
                    return Node(config, keys, store, replay, client, archive, crypto)
                } catch (failure: Throwable) {
                    client.close()
                    throw failure
                }
            } catch (failure: Throwable) {
                store.close()
                throw failure
            }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key

    val listener = LitebikeListenerElement(parentJob = supervisor)
    val inflight = Semaphore(32)

    /** `store: Mutex<Store>`: one holder at a time, never across a peer call. */
    val store_lock = Mutex()
    val replay_lock = Mutex()

    /** `reconciling`: one reconciliation at a time; a concurrent attempt is "reconciliation busy". */
    val reconciling = Mutex()

    /** The serve loop and every admitted request. */
    val serving = SupervisorJob(supervisor)

    /** `self.store()?`: [block] over the store under its lock. */
    suspend fun <T> store(block: (Store) -> T): T = store_lock.withLock { block(store) }

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        if (!config.member(config.id).key().contentEquals(signing_key.publicKey)) error("local key mismatch")
        super.open()
        listener.open()
        val requests = listener.register(Protocol.Http)
        val scope = CoroutineScope(currentCoroutineContext() + serving)
        scope.launch {
            while (true) {
                val message = try {
                    requests.consume()
                } catch (_: ClosedReceiveChannelException) {
                    break
                }
                scope.launch {
                    val response = try {
                        router(message.payload)
                    } catch (failure: Throwable) {
                        // An unanswerable request (a route that is still TODO) closes its connection unanswered.
                        message.respond?.invoke(ByteArray(0))
                        throw failure
                    }
                    message.respond?.invoke(response)
                }
            }
        }
        state = ElementState.ACTIVE
    }

    /**
     * network.rs Node::run: the HTTP service fed by [serve] (the platform bind adapter on [listener]) and the periodic
     * repair, replication and handoff every `reconcile_ms`, until [shutdown] returns. Local errors fail the service;
     * remote outages produce degraded custody. Either way the node drains, within the 5-second shutdown deadline.
     */
    suspend fun run(serve: suspend () -> Unit, shutdown: suspend () -> Unit) {
        open()
        val failure = supervisorScope {
            val server = async { serve() }
            val periodic = async { periodic() }
            val stop = async { shutdown() }
            val ended = select<Throwable?> {
                server.onJoin { IllegalStateException("mesh listener failed") }
                periodic.onJoin {
                    try {
                        periodic.await()
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
                stop.onJoin { null }
            }
            periodic.cancelAndJoin()
            stop.cancelAndJoin()
            val drained = try {
                drain()
                null
            } catch (failure: IllegalStateException) {
                failure
            }
            server.cancelAndJoin()
            ended ?: drained
        }
        if (failure != null) throw failure
    }

    /** run's `interval(reconcile_ms)`: the first tick at once, missed ticks skipped. */
    suspend fun periodic() {
        val period = config.reconcile_ms.toLong()
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            store { it.healthy() }
            // A serverless node makes no background peer calls: an external signed request wakes lifecycle work.
            if (!config.runpod_serverless) try {
                reconcile_attempt()
            } catch (busy: IllegalStateException) {
                if (busy is CancellationException || busy.message != "reconciliation busy") throw busy
            }
            delay(period - start.elapsedNow().inWholeMilliseconds % period)
        }
    }

    override suspend fun drain() {
        if (state != ElementState.ACTIVE) return
        state = ElementState.DRAINING
        listener.unregister(Protocol.Http)
        serving.complete()
        val joined = withTimeoutOrNull(5_000) { serving.join() } != null
        if (!joined) serving.cancelAndJoin()
        listener.close()
        gcs?.close()
        client.drain()
        store_lock.withLock { store.close() }
        supervisor.complete()
        supervisor.join()
        state = ElementState.CLOSED
        if (!joined) error("mesh shutdown deadline")
    }

    override suspend fun close() {
        listener.close()
        super.close()
    }

    /** One complete HTTP/1.1 request to its response, through [handle]. */
    suspend fun router(request: ByteArray): ByteArray {
        val boundary = request.indexOfHeaderBoundary()
        val head = if (boundary < 0) emptyList() else request.decodeToString(0, boundary).split("\r\n")
        val line = head.firstOrNull()?.split(' ')
        val response = if (line == null || line.size != 3) rejected(400) else {
            val length = head.drop(1).mapNotNull(::parseHeaderLine).toSeries().headerValue("Content-Length")?.trim()?.toIntOrNull() ?: 0
            handle(line[0], line[1], request.copyOfRange(boundary + 4, minOf(request.size, boundary + 4 + length)))
        }
        val body = response.payloadBytes
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "Service Unavailable"
        }
        val type = if (body.isEmpty()) "" else "content-type: ${response.contentType}\r\n"
        return "HTTP/1.1 ${response.status} $reason\r\n${type}content-length: ${body.size}\r\nconnection: close\r\n\r\n".encodeToByteArray() + body
    }

    /** network.rs handle: the status of each refusal, else 200 with the response signed over `[code, payload]`. */
    suspend fun handle(method: String, uri: String, body: ByteArray): WireHttpResponse {
        if (method == "GET" && uri == "/ping") return if (ok { store { it.healthy() } } != null) WireHttpResponse(200, "") else WireHttpResponse(503, "")
        if (!inflight.tryAcquire()) return rejected(503)
        try {
            if ('?' in uri) return rejected(400)
            if (body.size > MAX_WIRE) return rejected(413)
            val request = ok { Message.from_bytes(body) } ?: return rejected(401)
            ok { authenticate(request, method, uri) } ?: return rejected(401)
            val (code, payload) = ok { 0L j operation(request) } ?: (1L j ByteArray(0))
            val response = ok { Message.response(request, Cbor.encode(itemArrayOf(Item.Num(code), Item.Bin(payload))), signing_key.privateKey, crypto) }
                ?: return rejected(500)
            return WireHttpResponse(200, "", "application/cbor", response.to_bytes())
        } finally {
            inflight.release()
        }
    }

    /** network.rs Node::authenticate: binding, signature and freshness, the role gate, then the persisted replay table. */
    suspend fun authenticate(request: Message, method: String, path: String) {
        if (request.target != config.id || request.method != method || request.path != path) error("request binding")
        val member = config.member(request.source)
        request.verify(member.key(), false, crypto)
        val allowed = when {
            method == "GET" && path in setOf("/v1/status", "/v1/list", "/v1/pull", "/v1/settlement") ->
                member.has(Role.Replica) || member.has(Role.Archive) || member.has(Role.Reader) || member.has(Role.Admin)
            method == "POST" && path == "/v1/push" -> member.has(Role.Replica) || member.has(Role.Producer) || member.has(Role.Admin)
            method == "POST" && path in setOf("/v1/reconcile", "/v1/delete", "/v1/drain", "/v1/custody") -> member.has(Role.Admin)
            method == "POST" && path in setOf("/v1/settle", "/v1/lease", "/v1/ledger", "/v1/ledger/settle") -> member.has(Role.Admin)
            method == "GET" && path in setOf("/v1/ledger/head", "/v1/ledger/streams") ->
                member.has(Role.Replica) || member.has(Role.Reader) || member.has(Role.Admin)
            method == "POST" && path in setOf("/v1/ledger/adopt", "/v1/ledger/adopt-settled") -> member.has(Role.Replica)
            method == "POST" && path == "/v1/settlement/adopt" -> member.has(Role.Replica)
            method == "POST" && path == "/v1/drainto" -> config.legacy_archive_test_only && (member.has(Role.Replica) || member.has(Role.Admin))
            else -> false
        }
        if (!allowed) error("role denied")
        replay_lock.withLock {
            val now = now()
            replay.entries.removeAll { it.value < now }
            val k = request.source to request.nonce.toHexString()
            if (k in replay || replay.size.toULong() >= config.replay_capacity) error("replay or replay capacity")
            replay[k] = request.time + CLOCK_SKEW
            val rows = Item.Arr(
                replay.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).toSeries() α {
                    itemArrayOf(Item.Str(it.key.first), Item.Bin(it.key.second.hexToByteArray()), Item.Num(it.value.toLong()))
                }
            )
            val data = Cbor.encode(itemArrayOf(Item.Str("loom-replay/v1"), rows, Item.Bin(sha256(Cbor.encode(rows)))))
            store { it.save_replay(data) }
        }
    }

    /** network.rs Node::operation, the route table over the store, the peer client and the archive. */
    suspend fun operation(request: Message): ByteArray = when (request.path) {
        "/v1/settle" -> settle(Completion.from_bytes(request.payload))
        "/v1/lease" -> {
            val now = now()
            val lease = Lease.from_json(request.payload)
            when (lease.provider()) {
                Provider.Google -> (gcs ?: error("GCS missing")).install_lease(lease, now)
                Provider.Runpod -> {
                    if (config.members.view.none { it.has(Role.Replica) && it.url == lease.scope }) error("invocation lease origin not enrolled")
                    client.install_invocation_lease(lease, now)
                }
                // Cloudflare R2 and Vast leases are keeper credentials; the charter keeps GCS as the settlement archive.
                else -> error("lease provider not accepted by mesh")
            }
            Cbor.encode(itemArrayOf(Item.Str(RECEIPT_FORMAT), Item.Num(lease.not_after.toLong())))
        }
        "/v1/ledger" -> {
            val version = ledger_admit(request.payload)
            val accepted = store { it.update_ledger(version.stream) { s -> s.accept(version) } }
            ledger_head(version.stream, if (accepted == Accepted.Stale) 1uL else 1uL + ledger_spread(version), false)
        }
        "/v1/ledger/adopt" -> {
            val version = ledger_admit(request.payload)
            store { it.update_ledger(version.stream) { s -> s.accept(version) } }
            Cbor.encode(itemArrayOf())
        }
        "/v1/ledger/adopt-settled" -> {
            val pair = array(decode(request.payload), 2)
            val version = ledger_admit(bytes(pair[0]))
            val settled = LedgerSettled.from_bytes(bytes(pair[1]))
            settled.verify(config, version, crypto)
            if (settled.settled_at > now() + CLOCK_SKEW) error("ledger settlement from future")
            store { it.update_ledger(version.stream) { s -> s.record_settled(version, settled) } }
            Cbor.encode(itemArrayOf())
        }
        "/v1/ledger/settle" -> ledger_settle(text(decode(request.payload))).to_bytes()
        "/v1/ledger/head" -> ledger_head(text(decode(request.payload)), 1uL, false)
        "/v1/ledger/streams" -> {
            array(decode(request.payload), 0)
            val rows = store { s ->
                s.ledger_streams().map { stream ->
                    val slots = s.ledger(stream) ?: error("ledger missing")
                    itemArrayOf(Item.Str(stream), Item.Num(slots.settled_version().toLong()), Item.Num(slots.top().toLong()))
                }
            }
            Cbor.encode(itemArrayOf(rows))
        }
        "/v1/settlement/adopt" -> {
            val settlement = Settlement.from_bytes(request.payload)
            if (settlement.admitted_at > now()) error("admission from future")
            store { it.record_settlement(settlement) }
            Cbor.encode(itemArrayOf())
        }
        "/v1/settlement" -> {
            val id = fixed(decode(request.payload), 32)
            (store { it.settlement(id) } ?: error("completion missing")).to_bytes()
        }
        "/v1/custody" -> {
            val id = fixed(decode(request.payload), 32)
            (deadline(30_000, "custody deadline") { prove(id, request.nonce) } ?: error("segment archived and deleted")).to_bytes()
        }
        "/v1/push" -> {
            if (!config.member(config.id).has(Role.Replica)) error("replica role required")
            val s = Segment.from_bytes(request.payload)
            val id = store { it.ingest(s) }
            Receipt.issue_at(config.id, id, request.nonce, false, signing_key.privateKey, now(), crypto).to_bytes()
        }
        "/v1/drainto" -> {
            if (!config.member(config.id).has(Role.Archive)) error("archive role required")
            val segment = Segment.from_bytes(request.payload)
            val id = store { it.ingest(segment) }
            Receipt.issue_at(config.id, id, request.nonce, true, signing_key.privateKey, now(), crypto).to_bytes()
        }
        "/v1/drain" -> {
            val id = fixed(decode(request.payload), 32)
            if (!config.legacy_archive_test_only) (handoff(id) ?: error("GCS handoff unavailable")).to_bytes()
            else {
                val segment = store { it.get(id) }
                val destination = config.member(config.archive ?: error("archive missing"))
                val receipt = client.push_kind(destination, segment, "/v1/drainto", true)
                store { it.record_archive(id, receipt) }
                receipt.to_bytes()
            }
        }
        "/v1/delete" -> {
            if (!config.allow_delete || config.member(config.id).has(Role.Archive)) error("deletion disabled")
            val id = fixed(decode(request.payload), 32)
            if (config.legacy_archive_test_only) store { it.delete_archived(id, config.min_age_secs) } else delete_settled(id)
            Cbor.encode(itemArrayOf())
        }
        "/v1/pull" -> {
            val id = fixed(decode(request.payload), 32)
            store { it.get(id) }.to_bytes()
        }
        "/v1/list" -> {
            array(decode(request.payload), 0)
            val ids = store { s -> s.ids().also { ids -> for (id in ids) s.get(id) } }
            Cbor.encode(Item.Arr(ids.toSeries() α { Item.Bin(it) }))
        }
        "/v1/status" -> {
            array(decode(request.payload), 0)
            val ids = store { s -> s.ids().also { ids -> for (id in ids) s.get(id) } }
            Cbor.encode(itemArrayOf(Item.Str("loom-status/v1"), Item.Str(config.id), Item.Num(ids.size.toLong())))
        }
        "/v1/reconcile" -> {
            array(decode(request.payload), 0)
            Cbor.encode(Item.Arr(reconcile() α { itemArrayOf(Item.Bin(it.id), Item.Num(it.nodes.size.toLong())) }))
        }
        else -> error("unknown operation")
    }

    fun replica_peers(): Series<Member> = config.members.filter { it.id != config.id && it.has(Role.Replica) }

    suspend fun reconcile(): Series<Custody> {
        val attempt = reconcile_attempt()
        // Preserve the complete-only contract for existing and wire callers.
        if (!attempt.complete) error("reconciliation deadline")
        return attempt.custody
    }

    /**
     * Bound remote work without treating a partition as a local service failure. Apart from concurrent "busy" attempts,
     * errors remain local failures; deadline results are explicitly incomplete.
     */
    suspend fun reconcile_attempt(): Reconciliation {
        if (!reconciling.tryLock()) error("reconciliation busy")
        try {
            var backpressured_records = 0
            val custody = withTimeoutOrNull(30_000) { reconcile_inner { backpressured_records++ } }
            if (custody != null) return Reconciliation(true, custody, backpressured_records)
            // The cancelled traversal and its requests are dropped. Do not reuse old or partial remote proofs or skip
            // local integrity and write failures.
            return store { s ->
                // Cancellation discards the traversal snapshot, so validate the complete index before excluding any
                // tombstones from custody.
                s.verify_records()
                val proofs = s.ids().map { id ->
                    s.get(id)
                    val challenge = nonce()
                    val receipt = Receipt.issue_at(config.id, id, challenge, false, signing_key.privateKey, now(), crypto)
                    Custody.verified(id, challenge, s_[receipt], config.members, crypto)
                }
                Reconciliation(false, proofs.toSeries(), backpressured_records)
            }
        } finally {
            reconciling.unlock()
        }
    }

    suspend fun reconcile_inner(backpressure: () -> Unit): Series<Custody> {
        store { it.verify_records() }
        if (config.member(config.id).has(Role.Archive)) return emptySeriesOf()
        ledger_reconcile()
        val peers = replica_peers()
        // Repair from authenticated inventory, never trust inventory as possession evidence.
        for (peer in peers.view) {
            val ids = ok { client.list(peer) } ?: continue
            for (id in ids.view) {
                if (store { it.has_record(id) }) continue
                val segment = ok { client.pull(peer, id) } ?: continue
                store { s ->
                    // The missing ID may have been ingested and then legitimately deleted while the pull was in flight.
                    if (s.has_record(id) && s.live_segment(id) == null) return@store
                    try {
                        s.ingest(segment)
                    } catch (refused: IllegalStateException) {
                        if (refused.message in setOf("storage count", "storage capacity", "lifecycle headroom")) backpressure() else throw refused
                    }
                }
            }
        }
        val report = mutableListOf<Custody>()
        for (id in store { it.ids() }) {
            val segment = store { it.live_segment(id) } ?: continue
            // Ask by retained ID, including peers whose inventory no longer lists this record after reclaim. A signed
            // historical admission propagates the existing transition; it never counts as current custody.
            if (config.settlement_authority != null && store { it.settlement(id) } == null) {
                for (peer in peers.view) {
                    val data = ok { client.call(peer, "GET", "/v1/settlement", Cbor.encode(Item.Bin(id))) } ?: continue
                    val admission = ok { Settlement.from_bytes(data) } ?: continue
                    if (admission.admitted_at <= now() && ok { admission.verify(config, id, crypto) } != null) {
                        store { it.record_settlement(admission) }
                        break
                    }
                }
            }
            val custody = prove(id, nonce()) ?: continue
            if (gcs != null && store { it.settlement(id) } != null) handoff(id)
            config.archive?.let { archive ->
                val destination = config.member(archive)
                ok { client.push_kind(destination, segment, "/v1/drainto", true) }?.let { receipt -> store { it.record_archive(id, receipt) } }
            }
            report.add(custody)
        }
        // A delete may finish during archive handoff or a later record's work. Validate the final report atomically
        // against current local custody; only verified tombstones are omitted, and storage errors still fail.
        return store { s ->
            s.verify_records()
            report.filter { s.live_segment(it.id) != null }.toSeries()
        }
    }

    suspend fun prove(id: Id, challenge: ByteArray): Custody? {
        val segment = store { it.live_segment(id) } ?: return null
        if (!config.member(config.id).has(Role.Replica)) error("custody is replica-only")
        val receipts = mutableListOf<Receipt>()
        for (peer in replica_peers().view) ok { client.push_challenge(peer, segment, "/v1/push", false, challenge) }?.let { receipts.add(it) }
        // Network awaits permit an authorized deletion (or a local failure). Revalidate under the lock before issuing
        // any local custody evidence.
        return store { s ->
            if (s.live_segment(id) == null) return@store null
            receipts.add(0, Receipt.issue_at(config.id, id, challenge, false, signing_key.privateKey, now(), crypto))
            Custody.verified(id, challenge, receipts.toSeries(), config.members, crypto)
        }
    }

    /** Remote failures are a retriable null; local integrity and persistence errors propagate so the periodic service still fails closed. */
    suspend fun handoff(id: Id): Handoff? {
        val gcs = gcs ?: error("GCS missing")
        var previous: Handoff? = null
        val (segment, admission) = store { s ->
            s.verify_records()
            val admission = s.settlement(id) ?: error("unsettled handoff")
            previous = s.handoff(id)
            s.live_segment(id)?.let { it j admission }
        } ?: return previous
        propagate(admission)
        val completion = admission.completion
        val bundle = settlement_bundle(segment, completion)
        val held = previous
        val result = ok {
            val receipt = held?.receipt ?: gcs.commit(bundle_name(bundle), bundle)
            gcs.verify(receipt, bundle)
            receipt
        }
        return store { s ->
            s.verify_records()
            if (s.settlement(id)?.completion?.to_bytes()?.contentEquals(completion.to_bytes()) != true) error("completion changed during handoff")
            val receipt = result ?: return@store null
            Handoff.issue(config, segment, completion, receipt, signing_key.privateKey, crypto).also { s.record_handoff(id, it) }
        }
    }

    suspend fun delete_settled(id: Id) {
        val (segment, completion, handoff) = store { s ->
            s.verify_records()
            val completion = (s.settlement(id) ?: error("unsettled deletion")).completion
            val handoff = s.handoff(id) ?: error("handoff missing")
            Triple(s.live_segment(id), completion, handoff)
        }
        if (segment != null) {
            val bundle = settlement_bundle(segment, completion)
            val archive = gcs ?: error("GCS missing")
            val failure = try {
                archive.verify(handoff.receipt, bundle)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                failed
            }
            store { s ->
                s.verify_records()
                if (s.handoff(id)?.to_bytes()?.contentEquals(handoff.to_bytes()) != true) error("handoff changed during deletion")
            }
            if (failure != null) throw failure
        }
        store { it.delete_settled(id, config.min_age_secs) }
    }

    /**
     * Retry dissemination of the committed decision, never require a new custody quorum. Return the signed admission
     * to callers for durable retry.
     */
    suspend fun propagate(admission: Settlement) {
        withTimeoutOrNull(30_000) {
            for (peer in replica_peers().view) ok { client.call(peer, "POST", "/v1/settlement/adopt", admission.to_bytes()) }
        }
        store { it.verify_records() }
    }

    fun ledger_admit(data: ByteArray): LedgerVersion {
        val version = LedgerVersion.from_bytes(data)
        version.verify(ledger_authority(config), crypto)
        if (version.issued_at > now() + CLOCK_SKEW) error("ledger version from future")
        return version
    }

    /** Push one version to every peer replica; returns how many hold it. */
    suspend fun ledger_spread(version: LedgerVersion): ULong {
        var held = 0uL
        val bytes = version.to_bytes()
        withTimeoutOrNull(30_000) {
            for (peer in replica_peers().view) if (ok { client.call(peer, "POST", "/v1/ledger/adopt", bytes) } != null) held++
        }
        return held
    }

    suspend fun ledger_head(stream: String, holders: ULong, uploaded: Boolean): ByteArray {
        val slots = store { it.ledger(stream) } ?: error("ledger stream missing")
        return LedgerHead.of(stream, slots, holders, uploaded).to_bytes()
    }

    /**
     * Minute 15 onward: send the closed epoch's final version upstream to the insert-only GCS object for its version
     * number, record the verified generation, and hand the settled pair to every replica. Returns the most recent
     * settled ledger for the caller's UI.
     */
    suspend fun ledger_settle(stream: String): LedgerHead {
        val slots = store { it.ledger(stream) } ?: error("ledger stream missing")
        val version = slots.candidate(now()) ?: return LedgerHead.of(stream, slots, 1uL, false)
        val gcs = gcs ?: error("GCS missing")
        // N=3 holds the unsettled version before it leaves for upstream.
        ledger_spread(version)
        val receipt = gcs.commit(version.object_name(), version.to_bytes())
        val settled = LedgerSettled.issue(config, version, receipt, now(), signing_key.privateKey, crypto)
        store { it.update_ledger(stream) { s -> s.record_settled(version, settled) } }
        val held = store { it.ledger(stream) } ?: error("ledger stream missing")
        val (held_version, held_settled) = held.settled ?: error("ledger settlement missing")
        val pair = Cbor.encode(itemArrayOf(Item.Bin(held_version.to_bytes()), Item.Bin(held_settled.to_bytes())))
        var holders = 1uL
        withTimeoutOrNull(30_000) {
            for (peer in replica_peers().view) if (ok { client.call(peer, "POST", "/v1/ledger/adopt-settled", pair) } != null) holders++
        }
        return LedgerHead.of(stream, held, holders, true)
    }

    /** Repair from peers (a returning replica catches up on both slots), then move any closed epoch upstream. Remote failures stay retriable. */
    suspend fun ledger_reconcile() {
        if (config.settlement_authority == null) return
        val authority = ledger_authority(config)
        val empty = Cbor.encode(itemArrayOf())
        for (peer in replica_peers().view) {
            val data = ok { client.call(peer, "GET", "/v1/ledger/streams", empty) } ?: continue
            val rows = ok { decode(data) } as? Item.Arr ?: continue
            for (row in rows.items.view.take(MAX_LEDGER_STREAMS)) {
                val fields = ok { array(row, 3) } ?: continue
                val stream = ok { text(fields[0]) } ?: continue
                val settled = ok { number(fields[1]) } ?: continue
                val top = ok { number(fields[2]) } ?: continue
                ok { label(stream) } ?: continue
                val mine = store { it.ledger(stream) }
                if (settled <= (mine?.settled_version() ?: 0uL) && top <= (mine?.top() ?: 0uL)) continue
                val reply = ok { client.call(peer, "GET", "/v1/ledger/head", Cbor.encode(Item.Str(stream))) } ?: continue
                val head = ok { LedgerHead.from_bytes(reply) } ?: continue
                if (head.stream != stream) continue
                head.settled?.let { (v, s) ->
                    if (ok { v.verify(authority, crypto) } != null && ok { s.verify(config, v, crypto) } != null)
                        ok { store { it.update_ledger(stream) { slots -> slots.record_settled(v, s) } } }
                }
                for (v in head.pending.view) if (ok { v.verify(authority, crypto) } != null) ok { store { it.update_ledger(stream) { slots -> slots.accept(v) } } }
                store { it.healthy() }
            }
        }
        if (gcs != null) {
            val streams = store { it.ledger_streams() }
            val current = now()
            for (stream in streams) {
                if (store { it.ledger(stream) }?.candidate(current) != null) {
                    ok { ledger_settle(stream) }
                    store { it.healthy() }
                }
            }
        }
    }

    suspend fun settle(completion: Completion): ByteArray {
        completion.verify(config.member(config.settlement_authority ?: error("settlement authority missing")), completion.id, crypto)
        if (completion.completed_at > now()) error("completion from future")
        val previous = store { it.settlement(completion.id) }
        if (previous != null) {
            if (!previous.completion.to_bytes().contentEquals(completion.to_bytes())) error("conflicting completion")
            propagate(previous)
            return previous.to_bytes()
        }
        val custody = deadline(30_000, "settlement custody deadline") { prove(completion.id, nonce()) } ?: error("completion needs live segment")
        if (custody.degraded) error("completion requires fresh N3")
        val settlement = Settlement.admit_at(completion, config.id, custody.receipts, signing_key.privateKey, now(), crypto)
        val committed = store { s ->
            s.verify_records()
            s.record_settlement(settlement)
            s.settlement(settlement.completion.id) ?: error("completion missing")
        }
        propagate(committed)
        return committed.to_bytes()
    }
}

/** Rust's `Result::ok`: the value, or null where [block] failed; cancellation still propagates. */
inline fun <T> ok(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failed: Exception) {
    null
}

/** `tokio::time::timeout(..).map_err(|_| Error(message))`. */
suspend fun <T> deadline(ms: Long, message: String, block: suspend CoroutineScope.() -> T): T = try {
    withTimeout(ms, block)
} catch (expired: TimeoutCancellationException) {
    error(message)
}

/** The refusal body, `["loom-error/v1", 1]` in CBOR. */
fun rejected(status: Int): WireHttpResponse =
    WireHttpResponse(status, "", "application/cbor", Cbor.encode(itemArrayOf(Item.Str("loom-error/v1"), Item.Num(1))))

/** network.rs load_replay: the table [Store.load_replay] persisted, expired rows dropped. */
fun load_replay(store: Store, config: Config): Replay {
    val result: Replay = mutableMapOf()
    val data = store.load_replay()
    if (data != null) {
        val a = array(decode(data), 3)
        tag(a[0], "loom-replay/v1")
        if (!fixed(a[2], 32).contentEquals(sha256(Cbor.encode(a[1])))) error("replay checksum")
        val rows = a[1] as? Item.Arr ?: error("replay schema")
        if (rows.size > 512) error("replay bounds")
        for (row in rows.items.view) {
            val r = array(row, 3)
            val id = text(r[0])
            config.member(id)
            val n = fixed(r[1], 32)
            val expiry = number(r[2])
            if (expiry >= now() && result.put(id to n.toHexString(), expiry) != null) error("duplicate stored nonce")
        }
        if (result.size.toULong() > config.replay_capacity) error("replay capacity reduced")
    }
    return result
}
