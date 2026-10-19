package borg.trikeshed.reactor

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.FanoutEvent
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

// The TLS vocabulary and codec SPI, apart from TlsElement (TlsEndpoint.kt) and its CCEK/NioSupervisor wiring.
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

fun tlsFrames(vararg frames: TlsChannelFrame): TlsFrames = frames.toSeries() // Bolt: Use .toSeries() directly to avoid intermediate List allocation

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
    val protocols: TlsProtocols = TlsProtocol.entries.toSeries(),
    val cipherSuites: TlsCipherSuites = TlsCipherSuite.entries.toSeries(),
    val supportedGroups: TlsSupportedGroups = TlsSupportedGroup.entries.toSeries(),
    val alpnProtocols: TlsApplicationProtocols = TlsApplicationProtocol.entries.toSeries(),
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

enum class TlsApplicationProtocol(val wireName: String) {
    H2("h2"),
    HTTP_1_1("http/1.1"),
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
    val applicationProtocol: TlsApplicationProtocol? = null,
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

interface TlsCodecBackend : CoroutineContext.Element {
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

    /**
     * A [TlsRecordSession] for an event loop that owns the connection's socket and thread, when this backend has
     * one; the suspend codec above remains the general path.
     */
    fun recordSession(config: TlsConfig, route: TlsRoute): TlsRecordSession =
        throw UnsupportedOperationException("${this::class.simpleName} drives TLS through its suspend codec only")
}

/** The TLS codec of a target without a TLS engine binding: every operation is the marked gap. */
class StubTlsCodecBackend : TlsCodecBackend {
    override suspend fun handshake(config: TlsConfig, state: TlsFlowState): TlsCodecResult =
        TODO("TLS codec for this target")
    override suspend fun upstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult =
        TODO("TLS codec for this target")
    override suspend fun downstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult =
        TODO("TLS codec for this target")
    override suspend fun close(config: TlsConfig, state: TlsFlowState): TlsCodecResult =
        TODO("TLS codec for this target")
}
