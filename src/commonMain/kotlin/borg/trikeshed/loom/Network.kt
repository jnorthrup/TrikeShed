package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.context.*
import borg.trikeshed.htx.*
import borg.trikeshed.ipns.*
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.*
import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.*
import kotlin.coroutines.CoroutineContext

/** network.rs `type Replay`: (source, nonce as lowercase hex) to expiry, in BTreeMap order when persisted. */
typealias Replay = MutableMap<Pair<String, String>, ULong>

/**
 * cocaine-rats crates/loom-mesh/src/network.rs Node without its Store: [handle] is the HTTP handler (statuses, decode
 * bounds, [authenticate] with the role gate and the replay table, the signed `[code, payload]` response) and [open]
 * serves it on the node's own [listener], which the platform bind adapter feeds one complete HTTP request at a time.
 * The Store's parts arrive as parameters: [operation] holds the route bodies, [save_replay] persists the replay table,
 * [healthy] answers /ping. Drain stops admission at the listener, answers what was admitted, joins, then closes.
 */
class Node(
    val config: Config,
    val signing_key: IpnsKeyPair,
    val operation: suspend (Message) -> ByteArray = ::operation,
    val save_replay: (ByteArray) -> Unit = { TODO("network.rs authenticate: Store::save_replay (storage.rs)") },
    val healthy: () -> Unit = { TODO("network.rs /ping: Store::healthy (storage.rs)") },
    val replay: Replay = mutableMapOf(),
    val crypto: IpnsCrypto = Ed25519,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<Node>()

    override val key: CoroutineContext.Key<*> get() = Key

    val listener = LitebikeListenerElement(parentJob = supervisor)
    val inflight = Semaphore(32)
    val replay_lock = Mutex()

    /** The serve loop and every admitted request. */
    val serving = SupervisorJob(supervisor)

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

    override suspend fun drain() {
        if (state != ElementState.ACTIVE) return
        state = ElementState.DRAINING
        listener.unregister(Protocol.Http)
        serving.complete()
        serving.join()
        listener.close()
        supervisor.complete()
        supervisor.join()
        state = ElementState.CLOSED
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
        if (method == "GET" && uri == "/ping") return try {
            healthy()
            WireHttpResponse(200, "")
        } catch (failure: Exception) {
            WireHttpResponse(503, "")
        }
        if (!inflight.tryAcquire()) return rejected(503)
        try {
            if ('?' in uri) return rejected(400)
            if (body.size > MAX_WIRE) return rejected(413)
            val request = try {
                Message.from_bytes(body)
            } catch (failure: Exception) {
                return rejected(401)
            }
            try {
                authenticate(request, method, uri)
            } catch (failure: Exception) {
                return rejected(401)
            }
            val (code, payload) = try {
                0L j operation(request)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                1L j ByteArray(0)
            }
            return try {
                val response = Message.response(request, Cbor.encode(itemArrayOf(Item.Num(code), Item.Bin(payload))), signing_key.privateKey, crypto)
                WireHttpResponse(200, "", "application/cbor", response.to_bytes())
            } catch (failure: Exception) {
                rejected(500)
            }
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
            save_replay(Cbor.encode(itemArrayOf(Item.Str("loom-replay/v1"), rows, Item.Bin(sha256(Cbor.encode(rows))))))
        }
    }
}

/**
 * network.rs Node::operation, the route table. Each body runs over the node's Store (storage.rs), /v1/lease over the
 * lease keeper; [Node] takes the route function as a parameter, so the Store-backed bodies replace this one.
 */
suspend fun operation(request: Message): ByteArray = when (request.path) {
    "/v1/lease" -> TODO("network.rs /v1/lease: Lease::from_json, GcsArchive::install_lease and Client::install_invocation_lease (the lease keeper)")
    "/v1/settle", "/v1/ledger", "/v1/ledger/adopt", "/v1/ledger/adopt-settled", "/v1/ledger/settle", "/v1/ledger/head",
    "/v1/ledger/streams", "/v1/settlement/adopt", "/v1/settlement", "/v1/custody", "/v1/push", "/v1/drainto", "/v1/drain",
    "/v1/delete", "/v1/pull", "/v1/list", "/v1/status", "/v1/reconcile",
    -> TODO("network.rs ${request.path}: the route body over the node's Store (storage.rs)")
    else -> error("unknown operation")
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
