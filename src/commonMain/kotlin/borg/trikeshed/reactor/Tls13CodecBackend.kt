package borg.trikeshed.reactor

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TlsCodecBackend] over [Tls13Client], the codec of targets without a platform TLS engine: client connections by
 * connection ordinal, the server certificate verified against the PEM certificates of [TlsConfig.trustStore] (read
 * through [fileOperations]) or else [TLS_SERVER_ROOTS], its name checked when [TlsConfig.hostnameVerification]. Flow
 * state and frames are the JVM backend's; a fatal alert surfaces as a thrown [TlsAlert].
 */
class Tls13CodecBackend(val fileOperations: FileOperations? = null) : TlsCodecBackend {
    val clients = HashMap<Long, Tls13Client>()
    val mutex = Mutex()

    fun client(config: TlsConfig, state: TlsFlowState): Tls13Client {
        if (state.route.a == TlsRole.SERVER) TODO("TLS 1.3 server")
        if (config.protocols.view.none { it == TlsProtocol.TLS13 }) TODO("TLS 1.2 client")
        if (config.certificateFile != null) TODO("TLS 1.3 client certificate")
        val anchors = config.trustStore?.let { path ->
            val operations = requireNotNull(fileOperations) { "TlsConfig.trustStore $path needs FileOperations" }
            pemCertificates(operations.readString(path)).map { TrustAnchor.of(it) }.toTypedArray().toSeries()
        } ?: TLS_SERVER_ROOTS
        return Tls13Client(
            serverName = state.route.b.a,
            verifyName = config.hostnameVerification,
            anchors = anchors,
            cipherSuites = config.cipherSuites.toList().filter { it.codePoint != null },
            groups = config.supportedGroups.toList().filter { it.codePoint != null },
            applicationProtocols = config.alpnProtocols.toList().map { it.protocolName },
            time = { kotlinx.datetime.Clock.System.now().epochSeconds },
        )
    }

    fun session(client: Tls13Client): TlsSession? {
        if (client.state != Tls13Client.State.CONNECTED) return null
        val endEntity = client.endEntity!!
        return TlsSession(
            protocol = TlsProtocol.TLS13,
            cipherSuite = client.suite,
            peerCertificate = TlsPeerCertificate(subject = rfc4514(endEntity.subject), issuer = rfc4514(endEntity.issuer)),
            verified = true,
        )
    }

    fun lifecycle(client: Tls13Client): TlsConnectionState = when {
        client.closed -> TlsConnectionState.CLOSED
        client.state == Tls13Client.State.CONNECTED -> TlsConnectionState.OPEN
        else -> TlsConnectionState.NEGOTIATING
    }

    fun frames(vararg frames: TlsChannelFrame?): TlsFrames {
        val present = frames.filterNotNull()
        return if (present.isEmpty()) emptyTlsFrames() else tlsFrames(*present.toTypedArray())
    }

    fun drain(parts: ArrayList<ByteArray>): ByteArray = Tls13Client.concat(parts).also { parts.clear() }

    override suspend fun handshake(config: TlsConfig, state: TlsFlowState): TlsCodecResult {
        val client = client(config, state)
        mutex.withLock { clients[state.connectionOrdinal] = client }
        val hello = client.clientHello()
        val next = state.copy(lifecycle = lifecycle(client), session = null, pendingUpstreamCiphertext = ByteSeries(hello))
        return TlsCodecResult(
            next,
            frames(
                TlsChannelFrame(route = state.route, stage = TlsFlowStage.HANDSHAKE),
                TlsChannelFrame(route = state.route, stage = TlsFlowStage.UPSTREAM_CIPHERTEXT, payload = ByteSeries(hello)),
            ),
        )
    }

    override suspend fun upstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult {
        val client = mutex.withLock { clients[state.connectionOrdinal] }
        check(client != null && client.state == Tls13Client.State.CONNECTED) {
            "TLS handshake not complete for ${state.route.b.a}:${state.route.b.b}"
        }
        val plaintext = payload.toArray()
        val ciphertext = client.seal(plaintext)
        val session = session(client) ?: state.session
        val next = state.copy(
            lifecycle = lifecycle(client),
            session = session,
            pendingUpstreamPlaintext = ByteSeries(plaintext),
            pendingUpstreamCiphertext = ByteSeries(ciphertext),
        )
        return TlsCodecResult(
            next,
            frames(
                plaintext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(route = state.route, stage = TlsFlowStage.UPSTREAM_PLAINTEXT, payload = ByteSeries(it), session = session)
                },
                ciphertext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(route = state.route, stage = TlsFlowStage.UPSTREAM_CIPHERTEXT, payload = ByteSeries(it), session = session)
                },
            ),
        )
    }

    override suspend fun downstream(config: TlsConfig, state: TlsFlowState, payload: TlsPayload): TlsCodecResult {
        val client = checkNotNull(mutex.withLock { clients[state.connectionOrdinal] }) {
            "TLS handshake not begun for ${state.route.b.a}:${state.route.b.b}"
        }
        val incoming = payload.toArray()
        val combined = state.pendingDownstreamCiphertext.toArray() + incoming
        val consumed = client.receive(combined)
        val outbound = drain(client.outgoing)
        val plaintext = drain(client.applicationData)
        val session = session(client) ?: state.session
        val next = state.copy(
            lifecycle = lifecycle(client),
            session = session,
            pendingUpstreamCiphertext = ByteSeries(outbound),
            pendingDownstreamCiphertext = ByteSeries(combined.copyOfRange(consumed, combined.size)),
            pendingDownstreamPlaintext = ByteSeries(plaintext),
        )
        return TlsCodecResult(
            next,
            frames(
                incoming.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(route = state.route, stage = TlsFlowStage.DOWNSTREAM_CIPHERTEXT, payload = ByteSeries(it), session = session)
                },
                (state.lifecycle != next.lifecycle || state.session == null && session != null).takeIf { it }?.let {
                    TlsChannelFrame(route = state.route, stage = TlsFlowStage.HANDSHAKE, session = session)
                },
                outbound.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(route = state.route, stage = TlsFlowStage.UPSTREAM_CIPHERTEXT, payload = ByteSeries(it), session = session)
                },
                plaintext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(route = state.route, stage = TlsFlowStage.DOWNSTREAM_PLAINTEXT, payload = ByteSeries(it), session = session)
                },
            ),
        )
    }

    override suspend fun close(config: TlsConfig, state: TlsFlowState): TlsCodecResult {
        val client = mutex.withLock { clients.remove(state.connectionOrdinal) }
        val ciphertext = client?.closeNotify() ?: ByteArray(0)
        val session = client?.let { session(it) } ?: state.session
        val next = state.copy(lifecycle = TlsConnectionState.CLOSED, session = session, pendingUpstreamCiphertext = ByteSeries(ciphertext))
        return TlsCodecResult(
            next,
            frames(TlsChannelFrame(route = state.route, stage = TlsFlowStage.CLOSE_NOTIFY, payload = ByteSeries(ciphertext), session = session)),
        )
    }
}
