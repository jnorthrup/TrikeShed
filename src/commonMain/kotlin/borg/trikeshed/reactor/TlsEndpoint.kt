package borg.trikeshed.reactor

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

typealias TlsProtocols = Series<TlsProtocol>
typealias TlsCipherSuites = Series<TlsCipherSuite>
typealias TlsSupportedGroups = Series<TlsSupportedGroup>
typealias TlsApplicationProtocols = Series<TlsApplicationProtocol>
typealias TlsPayload = ByteSeries
typealias TlsPeer = Join<String, Int>
typealias TlsRoute = Join<TlsRole, TlsPeer>
typealias TlsFrames = Series<TlsChannelFrame>
typealias TlsCodecResult = Join<TlsFlowState, TlsFrames>

fun emptyTlsFrames(): TlsFrames = emptySeriesOf()

fun tlsFrames(vararg frames: TlsChannelFrame): TlsFrames = frames.asList().toSeries()

fun TlsCodecResult(
    state: TlsFlowState,
    frames: TlsFrames = emptyTlsFrames(),
): TlsCodecResult = state j frames

val TlsCodecResult.state: TlsFlowState get() = a
val TlsCodecResult.frames: TlsFrames get() = b

@Serializable
data class TlsConfig(
    val trustStore: String? = null,
    val certificateFile: String? = null,
    val privateKeyFile: String? = null,
    val privateKeyPassword: String? = null,
    val protocols: TlsProtocols = TlsProtocol.entries.toTypedArray().toSeries(),
    val cipherSuites: TlsCipherSuites = TlsCipherSuite.entries.toTypedArray().toSeries(),
    val supportedGroups: TlsSupportedGroups = TlsSupportedGroup.entries.toTypedArray().toSeries(),
    val alpnProtocols: TlsApplicationProtocols = TlsApplicationProtocol.entries.toTypedArray().toSeries(),
    val clientAuth: ClientAuth = ClientAuth.NONE,
    val hostnameVerification: Boolean = true,
)

enum class TlsProtocol {
    TLS13,
    TLS12,
}

enum class ClientAuth {
    NONE,
    REQUESTED,
    REQUIRED,
}

enum class TlsRole {
    CLIENT,
    SERVER,
}

enum class TlsCipherSuite {
    TLS_AES_128_GCM_SHA256,
    TLS_AES_256_GCM_SHA384,
    TLS_CHACHA20_POLY1305_SHA256,
    ECDHE_RSA_AES128_GCM_SHA256,
    ECDHE_RSA_AES256_GCM_SHA384,
    ECDHE_ECDSA_CHACHA20_POLY1305,
    ECDHE_ECDSA_AES128_GCM_SHA256,
    ECDHE_ECDSA_AES256_GCM_SHA384,
}

enum class TlsSupportedGroup {
    X25519,
    P256,
    P384,
    P521,
}

enum class TlsApplicationProtocol {
    H2,
    HTTP_1_1,
}

data class TlsPeerCertificate(
    val subject: String? = null,
    val issuer: String? = null,
)

data class TlsSession(
    val protocol: TlsProtocol? = null,
    val cipherSuite: TlsCipherSuite? = null,
    val peerCertificate: TlsPeerCertificate? = null,
    val verified: Boolean = false,
)

enum class TlsFlowStage {
    HANDSHAKE,
    UPSTREAM_PLAINTEXT,
    UPSTREAM_CIPHERTEXT,
    DOWNSTREAM_CIPHERTEXT,
    DOWNSTREAM_PLAINTEXT,
    CLOSE_NOTIFY,
}

enum class TlsConnectionState {
    CREATED,
    NEGOTIATING,
    OPEN,
    DRAINING,
    CLOSED,
    FAILED,
}

data class TlsFlowState(
    val route: TlsRoute,
    val connectionOrdinal: Long = 0,
    val lifecycle: TlsConnectionState = TlsConnectionState.CREATED,
    val session: TlsSession? = null,
    val pendingUpstreamPlaintext: TlsPayload = ByteSeries(byteArrayOf()),
    val pendingUpstreamCiphertext: TlsPayload = ByteSeries(byteArrayOf()),
    val pendingDownstreamCiphertext: TlsPayload = ByteSeries(byteArrayOf()),
    val pendingDownstreamPlaintext: TlsPayload = ByteSeries(byteArrayOf()),
)

data class TlsChannelFrame(
    val route: TlsRoute,
    val stage: TlsFlowStage,
    val payload: TlsPayload = ByteSeries(byteArrayOf()),
    val session: TlsSession? = null,
) : FanoutEvent {
    override val eventType: Int = 0x544c53

    val role: TlsRole get() = route.a
    val remoteHost: String get() = route.b.a
    val remotePort: Int get() = route.b.b
}

interface TlsChannelSubscriber {
    suspend fun onTlsFrames(frames: TlsFrames)
}

interface TlsCodecBackend : KeyedService {
    companion object Key : CoroutineContext.Key<TlsCodecBackend>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun handshake(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult

    suspend fun upstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult

    suspend fun downstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult

    suspend fun close(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult
}

interface TlsFilterCodec {
    val route: TlsRoute
    val flowState: TlsFlowState
    val session: TlsSession? get() = flowState.session

    suspend fun handshake(): TlsFrames
    suspend fun upstream(payload: TlsPayload): TlsFrames
    suspend fun downstream(payload: TlsPayload): TlsFrames
    suspend fun close(): TlsFrames
}

interface TlsEndpoint : TlsFilterCodec {
    val role: TlsRole get() = route.a
    val remoteHost: String get() = route.b.a
    val remotePort: Int get() = route.b.b

    val isHandshakeComplete: Boolean
        get() = flowState.lifecycle == TlsConnectionState.OPEN && session != null
}

class TlsElement(
    val config: TlsConfig,
    private val backend: TlsCodecBackend,
    parentJob: kotlinx.coroutines.Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
    override val fanoutSubscribers: List<AsyncContextElement> = emptyList(),
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<TlsElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private val endpoints = linkedSetOf<BackendTlsEndpoint>()
    private var nextConnectionOrdinal = 1L

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
        }
    }

    fun clientEndpoint(hostname: String, port: Int = 443): TlsEndpoint =
        register(TlsRole.CLIENT j (hostname j port))

    fun serverEndpoint(bindHost: String = "0.0.0.0", port: Int = 443): TlsEndpoint =
        register(TlsRole.SERVER j (bindHost j port))

    private fun register(route: TlsRoute): TlsEndpoint =
        BackendTlsEndpoint(
            owner = this,
            config = config,
            backend = backend,
            initialState = TlsFlowState(
                route = route,
                connectionOrdinal = nextConnectionOrdinal++,
            ),
        ).also { endpoints += it }

    internal fun release(endpoint: TlsEndpoint) {
        if (endpoint is BackendTlsEndpoint) {
            endpoints -= endpoint
        }
    }

    internal suspend fun channelize(frames: TlsFrames) {
        fanoutSubscribers
            .filterIsInstance<TlsChannelSubscriber>()
            .forEach { it.onTlsFrames(frames) }
        val subscribers = fanoutSubscribers.filterIsInstance<FanoutEventSubscriber>()
        if (subscribers.isNotEmpty()) {
            frames.toList().forEach { frame ->
                subscribers.forEach { it.onFanoutEvent(frame) }
            }
        }
    }

    override suspend fun drain() {
        endpoints.toList().forEach { it.close() }
        super.drain()
    }

    override suspend fun close() {
        endpoints.toList().forEach { it.close() }
        endpoints.clear()
        if (ownedSupervisor != null && ownedSupervisor.state.isLessThan(ElementState.CLOSED)) {
            ownedSupervisor.close()
        }
        super.close()
    }
}

suspend fun openTlsElement(
    config: TlsConfig = TlsConfig(),
    backend: TlsCodecBackend,
    parentJob: kotlinx.coroutines.Job? = null,
    subscribers: List<AsyncContextElement> = emptyList(),
): TlsElement =
    TlsElement(
        config = config,
        backend = backend,
        parentJob = parentJob,
        fanoutSubscribers = subscribers,
    ).also { it.open() }

suspend fun openTlsElement(
    config: TlsConfig = TlsConfig(),
    nioSupervisor: NioSupervisor? = null,
    parentJob: kotlinx.coroutines.Job? = null,
    subscribers: List<AsyncContextElement> = emptyList(),
): TlsElement {
    val contextSupervisor = currentCoroutineContext()[NioSupervisor.Key]
    val activeSupervisor = nioSupervisor ?: contextSupervisor ?: NioSupervisor()
    val ownsSupervisor = nioSupervisor == null && contextSupervisor == null

    if (activeSupervisor.state == ElementState.CREATED) {
        activeSupervisor.open()
    }

    val backend = activeSupervisor.service<TlsCodecBackend>()
        ?: error("TlsElement requires TlsCodecBackend in NioSupervisor. Open the supervisor before installing TLS.")

    return TlsElement(
        config = config,
        backend = backend,
        parentJob = parentJob,
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
        fanoutSubscribers = subscribers,
    ).also { it.open() }
}

private class BackendTlsEndpoint(
    private val owner: TlsElement,
    private val config: TlsConfig,
    private val backend: TlsCodecBackend,
    initialState: TlsFlowState,
) : TlsEndpoint {
    private var closed = false

    override var flowState: TlsFlowState = initialState
        private set

    override val route: TlsRoute
        get() = flowState.route

    override suspend fun handshake(): TlsFrames {
        ensureOpen()
        return apply(backend.handshake(config, flowState))
    }

    override suspend fun upstream(payload: TlsPayload): TlsFrames {
        ensureOpen()
        return apply(backend.upstream(config, flowState, payload))
    }

    override suspend fun downstream(payload: TlsPayload): TlsFrames {
        ensureOpen()
        return apply(backend.downstream(config, flowState, payload))
    }

    override suspend fun close(): TlsFrames {
        if (closed) {
            return emptyTlsFrames()
        }
        closed = true
        val frames = apply(backend.close(config, flowState))
        owner.release(this)
        return frames
    }

    private fun ensureOpen() {
        check(!closed) { "TLS endpoint is closed" }
    }

    private suspend fun apply(result: TlsCodecResult): TlsFrames {
        flowState = result.state
        owner.channelize(result.frames)
        return result.frames
    }
}
