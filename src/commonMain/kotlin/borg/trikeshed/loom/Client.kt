package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.context.*
import borg.trikeshed.htx.*
import borg.trikeshed.ipns.*
import borg.trikeshed.lib.*
import borg.trikeshed.reactor.*
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/** cocaine-rats crates/loom-mesh/src/client.rs: at most this many replication pushes in flight. */
const val REPLICATION_CONCURRENCY: Int = 8

/** Less than the 30-second receipt-freshness window; each request keeps its 100..=10_000 ms timeout. */
const val REPLICATION_DEADLINE: Long = 20_000L

/** An unavailable peer is degraded evidence; an invalid answer or a TLS trust failure fails the operation. */
sealed class SendFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class Unavailable(message: String) : SendFailure(message)
    class Invalid(message: String, cause: Throwable? = null) : SendFailure(message, cause)
}

/**
 * A failure inside the TLS codec is not an ordinary unavailable replica: public errors stay redacted, and trust
 * failures are never hidden as degraded. The client's codec marks its own failures [SendFailure.Invalid].
 */
fun transport_failure(error: Throwable, message: String): SendFailure =
    if (generateSequence(error) { it.cause }.any { it is SendFailure.Invalid }) SendFailure.Invalid(message)
    else SendFailure.Unavailable(message)

/**
 * The signed-message client toward enrolled peers, over the HTX client reactor: http to loopback peers when allowed,
 * https through the platform TLS codec (the JDK's on the JVM). One request per connection, no proxy, no redirects.
 * Each exchange and each replication fan-out is admitted work under the client's supervisor; [drain] stops admission,
 * joins them and closes the HTTP stack.
 */
class Client(
    val id: String,
    val signing_key: IpnsKeyPair,
    val loopback: Boolean,
    val private_test: Boolean,
    val timeout_ms: ULong,
    val crypto: IpnsCrypto = Ed25519,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    init {
        label(id)
        if (timeout_ms !in 100uL..10_000uL) error("client timeout")
    }

    companion object Key : AsyncContextKey<Client>() {
        fun new(id: String, key: IpnsKeyPair, allow_loopback_http: Boolean, timeout_ms: ULong): Client =
            Client(id, key, allow_loopback_http, false, timeout_ms)

        fun insecure_local_test_only(id: String, key: IpnsKeyPair, timeout_ms: ULong): Client =
            Client(id, key, true, true, timeout_ms)
    }

    override val key: CoroutineContext.Key<*> get() = Key

    lateinit var http: HtxClientReactorElement
    lateinit var reactor: HtxReactorElement
    lateinit var tls: TlsCodecBackend

    /** The NIO supervisor this client opened because its context had none. */
    var nio: NioSupervisor? = null

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        val supervisorNio = currentCoroutineContext()[NioSupervisor] ?: NioSupervisor().also { it.open(); nio = it }
        val channels = supervisorNio.service<ChannelOperations>() ?: error("loom client requires ChannelOperations in NioSupervisor")
        val platform = supervisorNio.service<TlsCodecBackend>() ?: error("loom client requires TlsCodecBackend in NioSupervisor")
        tls = platform
        val codec = object : TlsCodecBackend by platform {
            override suspend fun handshake(config: TlsConfig, state: TlsFlowState): TlsCodecResult = try {
                platform.handshake(config, state)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw SendFailure.Invalid(failure.message ?: "TLS", failure)
            }

            override suspend fun upstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult = try {
                platform.upstream(config, state, payload)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw SendFailure.Invalid(failure.message ?: "TLS", failure)
            }

            override suspend fun downstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult = try {
                platform.downstream(config, state, payload)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw SendFailure.Invalid(failure.message ?: "TLS", failure)
            }
        }
        reactor = HtxReactorElement(channels, codec).also { it.open() }
        http = HtxClientReactorElement(reactor, HtxClientOptions(maxRedirects = 0)).also { it.open() }
        super.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        super.close()
        if (::http.isInitialized) http.close()
        if (::reactor.isInitialized) reactor.close()
        nio?.close()
    }

    /** A job for in-flight work under this client's supervisor, refused once [drain] has stopped admission. */
    fun admit(): CompletableJob {
        val work = SupervisorJob(supervisor)
        if (state != ElementState.ACTIVE || !work.isActive) {
            work.cancel()
            error("loom client is not accepting requests")
        }
        return work
    }

    /**
     * Push an already durably captured segment as an enrolled Producer to every enrolled replica under one challenge,
     * at most [REPLICATION_CONCURRENCY] at a time within [REPLICATION_DEADLINE]. An outage is degraded evidence, never
     * a quorum promise; an invalid successful reply fails the whole operation even if other peers acknowledged.
     */
    suspend fun replicate(segment: Segment, enrolled_members: Series<Member>): Custody {
        validate_producer_enrollment(segment, enrolled_members)
        val challenge = nonce()
        val replicas = enrolled_members.filter { it.has(Role.Replica) }
        val requests = admit()
        val receipts = mutableListOf<Receipt>()
        try {
            val scope = CoroutineScope(currentCoroutineContext() + requests)
            val peers = Channel<Member>(REPLICATION_CONCURRENCY)
            val outcomes = Channel<Result<Receipt>>(REPLICATION_CONCURRENCY)
            repeat(minOf(REPLICATION_CONCURRENCY, replicas.size)) {
                scope.launch {
                    for (peer in peers) outcomes.send(runCatching { push_challenge_checked(peer, segment, "/v1/push", false, challenge) })
                }
            }
            scope.launch {
                for (peer in replicas) peers.send(peer)
                peers.close()
            }
            withTimeoutOrNull(REPLICATION_DEADLINE) {
                repeat(replicas.size) {
                    outcomes.receive().onSuccess { receipts += it }.onFailure { failure ->
                        when (failure) {
                            is SendFailure.Unavailable -> Unit
                            is SendFailure.Invalid -> throw failure
                            else -> error("replication task failed")
                        }
                    }
                }
            }
        } finally {
            // Cancelling aborts any queued or in-flight request before the proof is returned.
            requests.cancelAndJoin()
        }
        return Custody.verified(segment.id(), challenge, receipts.toSeries(), enrolled_members, crypto)
    }

    fun validate_producer_enrollment(segment: Segment, members: Series<Member>) {
        if (members.size !in 1..64) error("producer enrollment bounds")
        val ids = mutableSetOf<String>()
        val keys = mutableSetOf<String>()
        val domains = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val producers = mutableMapOf<String, ByteArray>()
        for (member in members.view) {
            label(member.id)
            val key = member.key()
            if (!ids.add(member.id) || !keys.add(key.toHexString()) || member.roles.size == 0 || member.roles.size > 5 ||
                (0 until member.roles.size).any { i -> (0 until i).any { member.roles[it] == member.roles[i] } }
            ) error("duplicate or invalid producer enrollment")
            if (member.has(Role.Replica) || member.has(Role.Archive)) {
                label(member.domain)
                if (!domains.add(member.domain) || member.has(Role.Replica) && member.has(Role.Archive))
                    error("duplicate domain or mixed custody role")
                if (!urls.add(validate_url(member.url ?: error("peer URL missing"), loopback, private_test).toString())) error("duplicate peer URL")
            } else if (member.url != null || member.domain.isNotEmpty()) error("storage fields without storage role")
            if (member.has(Role.Producer)) producers[member.id] = key
        }
        val producer = members.view.firstOrNull { it.id == id } ?: error("producer not enrolled")
        if (!producer.has(Role.Producer) || !producer.key().contentEquals(signing_key.publicKey) || segment.producer != id)
            error("producer identity mismatch")
        segment.verify(producers, crypto)
    }

    suspend fun custody(peer: Member, id: Id, members: Series<Member>): Custody {
        val req = request(peer, "POST", "/v1/custody", Cbor.encode(Item.Bin(id)))
        val data = send(peer, req)
        return Custody.from_bytes(data, id, req.nonce, members, crypto)
    }

    fun request(peer: Member, method: String, path: String, payload: ByteArray): Message =
        Message.request(id, peer.id, method, path, payload, signing_key.privateKey, crypto)

    /** [send_checked] with the failure kind erased. */
    suspend fun send(peer: Member, request: Message): ByteArray = try {
        send_checked(peer, request)
    } catch (failure: SendFailure) {
        throw IllegalStateException(failure.message)
    }

    suspend fun send_checked(peer: Member, request: Message): ByteArray {
        val outgoing = outgoing(peer, request)
        val exchange = admit()
        val response = try {
            http.request(outgoing)
        } catch (failure: CancellationException) {
            // The reactor's exchange deadline arrives as a cancellation; the caller's own cancellation rethrows here.
            currentCoroutineContext().ensureActive()
            throw SendFailure.Unavailable("peer unavailable")
        } catch (failure: Exception) {
            throw transport_failure(failure, "peer unavailable")
        } finally {
            exchange.complete()
        }
        // Status 0: the peer closed or answered no HTTP response.
        if (response.status == 0) throw SendFailure.Unavailable("peer unavailable")
        if (response.status != 200) {
            val error = "peer rejected request"
            throw if (response.status in 500..599 || response.status == 429) SendFailure.Unavailable(error)
            else SendFailure.Invalid(error)
        }
        val length = response.headers.headerValue("Content-Length")?.trim()?.toLongOrNull()
        if (length != null && length > MAX_WIRE) throw SendFailure.Invalid("peer rejected request")
        val data = response.body.toArray()
        if (length != null && data.size < length) throw SendFailure.Unavailable("peer response read")
        if (data.size > MAX_WIRE) throw SendFailure.Invalid("peer response size")
        try {
            val reply = Message.from_bytes(data)
            reply.verify_response(request, peer, crypto)
            val a = array(decode(reply.payload), 2)
            // The generic authenticated rejection does not tell full storage from an integrity or configuration
            // failure; it is neither an outage nor ignorable.
            if (number(a[0]) != 0uL) throw SendFailure.Invalid("peer operation rejected")
            return bytes(a[1])
        } catch (failure: SendFailure) {
            throw failure
        } catch (failure: Exception) {
            throw SendFailure.Invalid(failure.message ?: "peer response")
        }
    }

    /**
     * The HTTP request for [request] to [peer]'s validated URL, the CBOR message as its body. A request path that is
     * not absolute or leaves visible ASCII is refused rather than joined or percent-encoded; the peer would refuse the
     * rewritten path anyway. The Runpod invocation Authorization header comes with the lease keeper.
     */
    fun outgoing(peer: Member, request: Message): HtxRequest {
        val base = try {
            validate_url(peer.url ?: throw SendFailure.Invalid("peer URL missing"), loopback, private_test)
        } catch (failure: SendFailure) {
            throw failure
        } catch (failure: IllegalStateException) {
            throw SendFailure.Invalid(failure.message ?: "unsafe URL")
        }
        if (base.host?.startsWith('[') == true) TODO("loom client: IPv6 peer literals (the HTX reactor connects over AF_INET only)")
        if (request.target != peer.id || request.source != id) throw SendFailure.Invalid("request identity")
        if (!request.path.startsWith('/') || request.path.any { it.code <= 0x20 || it.code >= 0x7f })
            throw SendFailure.Invalid("request URL")
        val method = HtxMethod.entries.firstOrNull { it.name == request.method } ?: throw SendFailure.Invalid("method")
        if (base.scheme == "https" && tls is StubTlsCodecBackend) TODO("loom client: https peers on this target wait on the TLS 1.3 client")
        return parseHtxRequest(base.serialization + request.path.substring(1), method = method, body = ByteSeries(request.to_bytes()))
            .copy(headers = htxHeaders("content-type" j "application/cbor"), timeoutMs = timeout_ms.toLong())
    }

    suspend fun call(peer: Member, method: String, path: String, payload: ByteArray): ByteArray =
        send(peer, request(peer, method, path, payload))

    suspend fun push(peer: Member, segment: Segment): Receipt = push_kind(peer, segment, "/v1/push", false)

    suspend fun push_kind(peer: Member, segment: Segment, path: String, archival: Boolean): Receipt =
        push_challenge(peer, segment, path, archival, nonce())

    /** [push_challenge_checked] with the failure kind erased. */
    suspend fun push_challenge(peer: Member, segment: Segment, path: String, archival: Boolean, nonce: ByteArray): Receipt = try {
        push_challenge_checked(peer, segment, path, archival, nonce)
    } catch (failure: SendFailure) {
        throw IllegalStateException(failure.message)
    }

    suspend fun push_challenge_checked(peer: Member, segment: Segment, path: String, archival: Boolean, nonce: ByteArray): Receipt {
        val req = try {
            Message.request_at(id, peer.id, "POST", path, segment.to_bytes(), signing_key.privateKey, now() j nonce, crypto)
        } catch (failure: Exception) {
            throw SendFailure.Invalid(failure.message ?: "request")
        }
        val data = send_checked(peer, req)
        try {
            val receipt = Receipt.from_bytes(data)
            receipt.verify_fresh(peer, segment.id(), req.nonce, crypto)
            if (receipt.archival != archival) throw SendFailure.Invalid("receipt kind")
            return receipt
        } catch (failure: SendFailure) {
            throw failure
        } catch (failure: Exception) {
            throw SendFailure.Invalid(failure.message ?: "receipt")
        }
    }

    suspend fun pull(peer: Member, id: Id): Segment {
        val s = Segment.from_bytes(call(peer, "GET", "/v1/pull", Cbor.encode(Item.Bin(id))))
        if (!s.id().contentEquals(id)) error("pull content mismatch")
        return s
    }

    suspend fun list(peer: Member): Series<Id> {
        val items = decode(call(peer, "GET", "/v1/list", Cbor.encode(itemArrayOf()))) as? Item.Arr ?: error("list schema")
        if (items.size > 1024) error("list size")
        val ids = Array(items.size) { fixed(items[it], 32) }
        for (i in 1 until ids.size) if (ids[i - 1].toHexString() >= ids[i].toHexString()) error("list order")
        return ids.toSeries()
    }

    /** Endpoint-scoped Runpod edge credentials; the signed mesh authorization is unchanged. */
    fun with_runpod_tokens(tokens: Map<String, String>): Client =
        TODO("loom client: Runpod edge tokens (bootstrap::token_headers) and invocation leases (the lease keeper)")
}
