package borg.trikeshed.reactor

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

class JvmTlsCodecBackend : TlsCodecBackend, KeyedService {
    private data class EngineHandle(
        val engine: SSLEngine,
        var started: Boolean = false,
    )

    private data class DownstreamOutcome(
        val plaintext: ByteArray,
        val outboundCiphertext: ByteArray,
        val leftoverCiphertext: ByteArray,
        val closed: Boolean,
    )

    private val engines = ConcurrentHashMap<Long, EngineHandle>()

    override suspend fun handshake(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult {
        val handle = engineHandle(config, state)
        beginHandshake(handle)

        val outboundCiphertext = drainHandshakeCiphertext(handle.engine)
        val next = state.copy(
            lifecycle = lifecycleFor(handle, closed = false),
            session = sessionOf(handle.engine),
            pendingUpstreamCiphertext = ByteSeries(outboundCiphertext),
        )

        return TlsCodecResult(
            next,
            framesOf(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.HANDSHAKE,
                    session = next.session,
                ),
                outboundCiphertext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.UPSTREAM_CIPHERTEXT,
                        payload = ByteSeries(it),
                        session = next.session,
                    )
                },
            ),
        )
    }

    override suspend fun upstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult {
        val handle = engineHandle(config, state)
        check(lifecycleFor(handle, closed = false) == TlsConnectionState.OPEN) {
            "TLS handshake not complete for ${state.route.b.a}:${state.route.b.b}"
        }

        val plaintext = payload.toArray()
        val ciphertext = wrapApplicationCiphertext(handle.engine, plaintext)
        val session = sessionOf(handle.engine) ?: state.session
        val next = state.copy(
            lifecycle = lifecycleFor(handle, closed = false),
            session = session,
            pendingUpstreamPlaintext = ByteSeries(plaintext),
            pendingUpstreamCiphertext = ByteSeries(ciphertext),
        )

        return TlsCodecResult(
            next,
            framesOf(
                plaintext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.UPSTREAM_PLAINTEXT,
                        payload = ByteSeries(it),
                        session = session,
                    )
                },
                ciphertext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.UPSTREAM_CIPHERTEXT,
                        payload = ByteSeries(it),
                        session = session,
                    )
                },
            ),
        )
    }

    override suspend fun downstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult {
        val handle = engineHandle(config, state)
        beginHandshake(handle)

        val incoming = payload.toArray()
        val combined = state.pendingDownstreamCiphertext.toArray() + incoming
        val outcome = unwrapDownstream(handle.engine, combined)
        val session = sessionOf(handle.engine) ?: state.session
        val next = state.copy(
            lifecycle = lifecycleFor(handle, closed = outcome.closed),
            session = session,
            pendingUpstreamCiphertext = ByteSeries(outcome.outboundCiphertext),
            pendingDownstreamCiphertext = ByteSeries(outcome.leftoverCiphertext),
            pendingDownstreamPlaintext = ByteSeries(outcome.plaintext),
        )

        return TlsCodecResult(
            next,
            framesOf(
                incoming.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.DOWNSTREAM_CIPHERTEXT,
                        payload = ByteSeries(it),
                        session = session,
                    )
                },
                (state.lifecycle != next.lifecycle || state.session == null && session != null).takeIf { it }?.let {
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.HANDSHAKE,
                        session = session,
                    )
                },
                outcome.outboundCiphertext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.UPSTREAM_CIPHERTEXT,
                        payload = ByteSeries(it),
                        session = session,
                    )
                },
                outcome.plaintext.takeIf { it.isNotEmpty() }?.let {
                    TlsChannelFrame(
                        route = state.route,
                        stage = TlsFlowStage.DOWNSTREAM_PLAINTEXT,
                        payload = ByteSeries(it),
                        session = session,
                    )
                },
            ),
        )
    }

    override suspend fun close(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult {
        val handle = engineHandle(config, state)
        handle.engine.closeOutbound()
        val ciphertext = drainCloseCiphertext(handle.engine)
        val session = sessionOf(handle.engine) ?: state.session
        engines.remove(state.connectionOrdinal)

        val next = state.copy(
            lifecycle = TlsConnectionState.CLOSED,
            session = session,
            pendingUpstreamCiphertext = ByteSeries(ciphertext),
        )

        return TlsCodecResult(
            next,
            framesOf(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.CLOSE_NOTIFY,
                    payload = ByteSeries(ciphertext),
                    session = session,
                ),
            ),
        )
    }

    private fun engineHandle(
        config: TlsConfig,
        state: TlsFlowState,
    ): EngineHandle =
        engines.computeIfAbsent(state.connectionOrdinal) {
            EngineHandle(createEngine(config, state))
        }

    private fun beginHandshake(handle: EngineHandle) {
        if (!handle.started) {
            handle.engine.beginHandshake()
            handle.started = true
        }
    }

    private fun createEngine(
        config: TlsConfig,
        state: TlsFlowState,
    ): SSLEngine {
        val role = state.route.a
        val peer = state.route.b
        val context = createSslContext(config, role)
        val engine = if (role == TlsRole.CLIENT) {
            context.createSSLEngine(peer.a, peer.b)
        } else {
            context.createSSLEngine()
        }

        engine.useClientMode = role == TlsRole.CLIENT

        val enabledProtocols = config.protocols
            .toList()
            .mapNotNull(::protocolNameOf)
            .filter { it in engine.supportedProtocols.toSet() }
            .toTypedArray()
        if (enabledProtocols.isNotEmpty()) {
            engine.enabledProtocols = enabledProtocols
        }

        val enabledCipherSuites = config.cipherSuites
            .toList()
            .mapNotNull(::cipherSuiteNameOf)
            .filter { it in engine.supportedCipherSuites.toSet() }
            .toTypedArray()
        if (enabledCipherSuites.isNotEmpty()) {
            engine.enabledCipherSuites = enabledCipherSuites
        }

        val parameters = engine.sslParameters
        val alpnProtocols = config.alpnProtocols.toList().map(::applicationProtocolNameOf).toTypedArray()
        if (alpnProtocols.isNotEmpty()) {
            parameters.applicationProtocols = alpnProtocols
        }
        val namedGroups = config.supportedGroups.toList().map(::namedGroupOf).toTypedArray()
        if (namedGroups.isNotEmpty()) {
            parameters.namedGroups = namedGroups
        }
        if (role == TlsRole.CLIENT && config.hostnameVerification) {
            parameters.endpointIdentificationAlgorithm = "HTTPS"
        }
        engine.sslParameters = parameters

        when (config.clientAuth) {
            ClientAuth.NONE -> {
                engine.needClientAuth = false
                engine.wantClientAuth = false
            }
            ClientAuth.REQUESTED -> engine.wantClientAuth = true
            ClientAuth.REQUIRED -> engine.needClientAuth = true
        }

        return engine
    }

    private fun createSslContext(
        config: TlsConfig,
        role: TlsRole,
    ): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(
            keyManagers(config, role),
            trustManagers(config),
            null,
        )
        return context
    }

    private fun keyManagers(
        config: TlsConfig,
        role: TlsRole,
    ): Array<KeyManager>? {
        if (config.certificateFile == null || config.privateKeyFile == null) {
            check(role != TlsRole.SERVER) {
                "Server TLS requires certificateFile and privateKeyFile."
            }
            return null
        }

        val certs = loadCertificates(Path.of(config.certificateFile))
        val privateKey = loadPrivateKey(Path.of(config.privateKeyFile), certs.first().publicKey.algorithm)
        val password = (config.privateKeyPassword ?: "changeit").toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("tls", privateKey, password, certs.toTypedArray())

        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, password)
        return factory.keyManagers
    }

    private fun trustManagers(config: TlsConfig): Array<TrustManager> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        val trustStore = config.trustStore?.let {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                loadCertificates(Path.of(it)).forEachIndexed { index, cert ->
                    setCertificateEntry("trust-$index", cert)
                }
            }
        }
        factory.init(trustStore)
        return factory.trustManagers
    }

    private fun unwrapDownstream(
        engine: SSLEngine,
        ciphertext: ByteArray,
    ): DownstreamOutcome {
        val source = ByteBuffer.wrap(ciphertext)
        val plaintext = ByteArrayOutputStream()
        val outbound = ByteArrayOutputStream()
        var closed = false

        while (source.hasRemaining()) {
            runDelegatedTasks(engine)

            var appCapacity = applicationBufferSize(engine)
            var result: SSLEngineResult
            while (true) {
                val appBuffer = ByteBuffer.allocate(appCapacity)
                val position = source.position()
                result = engine.unwrap(source, appBuffer)
                when (result.status) {
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        source.position(position)
                        appCapacity *= 2
                    }
                    else -> {
                        appBuffer.flip()
                        if (appBuffer.hasRemaining()) {
                            plaintext.write(appBuffer.toByteArray())
                        }
                        break
                    }
                }
            }

            when (result.status) {
                SSLEngineResult.Status.OK -> Unit
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
                SSLEngineResult.Status.CLOSED -> {
                    closed = true
                    break
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> error("Unreachable buffer overflow handling")
            }

            if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                outbound.write(drainHandshakeCiphertext(engine))
                continue
            }

            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                break
            }
        }

        val outboundCiphertext = outbound.toByteArray() + drainHandshakeCiphertext(engine)
        val leftover = ByteArray(source.remaining())
        source.get(leftover)

        return DownstreamOutcome(
            plaintext = plaintext.toByteArray(),
            outboundCiphertext = outboundCiphertext,
            leftoverCiphertext = leftover,
            closed = closed,
        )
    }

    private fun wrapApplicationCiphertext(
        engine: SSLEngine,
        plaintext: ByteArray,
    ): ByteArray {
        val source = ByteBuffer.wrap(plaintext)
        val ciphertext = ByteArrayOutputStream()

        while (source.hasRemaining()) {
            ciphertext.write(wrapOnce(engine, source))
            runDelegatedTasks(engine)
        }

        ciphertext.write(drainHandshakeCiphertext(engine))
        return ciphertext.toByteArray()
    }

    private fun drainHandshakeCiphertext(engine: SSLEngine): ByteArray {
        val ciphertext = ByteArrayOutputStream()
        while (true) {
            runDelegatedTasks(engine)
            if (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                break
            }
            val frame = wrapOnce(engine, EMPTY_BUFFER.duplicate())
            if (frame.isEmpty()) {
                break
            }
            ciphertext.write(frame)
        }
        return ciphertext.toByteArray()
    }

    private fun drainCloseCiphertext(engine: SSLEngine): ByteArray {
        val ciphertext = ByteArrayOutputStream()
        while (!engine.isOutboundDone) {
            val frame = wrapOnce(engine, EMPTY_BUFFER.duplicate())
            if (frame.isEmpty()) {
                break
            }
            ciphertext.write(frame)
        }
        return ciphertext.toByteArray()
    }

    private fun wrapOnce(
        engine: SSLEngine,
        source: ByteBuffer,
    ): ByteArray {
        var packetCapacity = packetBufferSize(engine)

        while (true) {
            val packet = ByteBuffer.allocate(packetCapacity)
            val result = engine.wrap(source, packet)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> packetCapacity *= 2
                SSLEngineResult.Status.OK,
                SSLEngineResult.Status.CLOSED,
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    packet.flip()
                    return packet.toByteArray()
                }
            }
        }
    }

    private fun lifecycleFor(
        handle: EngineHandle,
        closed: Boolean,
    ): TlsConnectionState =
        when {
            closed || handle.engine.isInboundDone && handle.engine.isOutboundDone -> TlsConnectionState.CLOSED
            !handle.started -> TlsConnectionState.CREATED
            handshakeComplete(handle.engine) -> TlsConnectionState.OPEN
            else -> TlsConnectionState.NEGOTIATING
        }

    private fun handshakeComplete(engine: SSLEngine): Boolean =
        engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
            sessionOf(engine) != null &&
            !engine.isOutboundDone

    private fun sessionOf(engine: SSLEngine): TlsSession? {
        val session = engine.session ?: return null
        val protocol = protocolOf(session.protocol) ?: return null
        val cipherSuite = cipherSuiteOf(session.cipherSuite) ?: return null
        val peerCertificate = try {
            (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
                TlsPeerCertificate(
                    subject = it.subjectX500Principal?.name,
                    issuer = it.issuerX500Principal?.name,
                )
            }
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        val verified = try {
            session.peerCertificates.isNotEmpty()
        } catch (_: SSLPeerUnverifiedException) {
            false
        }
        return TlsSession(
            protocol = protocol,
            cipherSuite = cipherSuite,
            peerCertificate = peerCertificate,
            verified = verified,
        )
    }

    private fun loadCertificates(path: Path): List<X509Certificate> {
        val text = Files.readString(path)
        val blocks = pemBlocks(text, "CERTIFICATE")
        check(blocks.isNotEmpty()) { "No certificates found in $path" }
        val factory = CertificateFactory.getInstance("X.509")
        return blocks.map { block ->
            ByteArrayInputStream(block).use {
                factory.generateCertificate(it) as X509Certificate
            }
        }
    }

    private fun loadPrivateKey(
        path: Path,
        preferredAlgorithm: String,
    ): PrivateKey {
        val text = Files.readString(path)
        check("BEGIN ENCRYPTED PRIVATE KEY" !in text) {
            "Encrypted private keys are not supported yet for $path"
        }
        val block = pemBlocks(text, "PRIVATE KEY").firstOrNull()
            ?: error("Expected PKCS#8 private key in $path")
        val spec = PKCS8EncodedKeySpec(block)
        return sequenceOf(preferredAlgorithm, "RSA", "EC", "DSA")
            .distinct()
            .firstNotNullOfOrNull { algorithm ->
                runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull()
            }
            ?: error("Could not decode private key in $path")
    }

    private fun pemBlocks(
        text: String,
        label: String,
    ): List<ByteArray> =
        Regex(
            "-----BEGIN $label-----(.*?)-----END $label-----",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(text).map { match ->
            Base64.getMimeDecoder().decode(match.groupValues[1])
        }.toList()

    private fun runDelegatedTasks(engine: SSLEngine) {
        while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
    }

    private fun protocolNameOf(protocol: TlsProtocol): String =
        when (protocol) {
            TlsProtocol.TLS13 -> "TLSv1.3"
            TlsProtocol.TLS12 -> "TLSv1.2"
        }

    private fun protocolOf(name: String?): TlsProtocol? =
        when (name) {
            "TLSv1.3" -> TlsProtocol.TLS13
            "TLSv1.2" -> TlsProtocol.TLS12
            else -> null
        }

    private fun cipherSuiteNameOf(cipherSuite: TlsCipherSuite): String =
        when (cipherSuite) {
            TlsCipherSuite.TLS_AES_128_GCM_SHA256 -> "TLS_AES_128_GCM_SHA256"
            TlsCipherSuite.TLS_AES_256_GCM_SHA384 -> "TLS_AES_256_GCM_SHA384"
            TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256 -> "TLS_CHACHA20_POLY1305_SHA256"
            TlsCipherSuite.ECDHE_RSA_AES128_GCM_SHA256 -> "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
            TlsCipherSuite.ECDHE_RSA_AES256_GCM_SHA384 -> "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            TlsCipherSuite.ECDHE_ECDSA_CHACHA20_POLY1305 -> "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"
            TlsCipherSuite.ECDHE_ECDSA_AES128_GCM_SHA256 -> "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
            TlsCipherSuite.ECDHE_ECDSA_AES256_GCM_SHA384 -> "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
        }

    private fun cipherSuiteOf(name: String?): TlsCipherSuite? =
        when (name) {
            "TLS_AES_128_GCM_SHA256" -> TlsCipherSuite.TLS_AES_128_GCM_SHA256
            "TLS_AES_256_GCM_SHA384" -> TlsCipherSuite.TLS_AES_256_GCM_SHA384
            "TLS_CHACHA20_POLY1305_SHA256" -> TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" -> TlsCipherSuite.ECDHE_RSA_AES128_GCM_SHA256
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384" -> TlsCipherSuite.ECDHE_RSA_AES256_GCM_SHA384
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256" -> TlsCipherSuite.ECDHE_ECDSA_CHACHA20_POLY1305
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" -> TlsCipherSuite.ECDHE_ECDSA_AES128_GCM_SHA256
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384" -> TlsCipherSuite.ECDHE_ECDSA_AES256_GCM_SHA384
            else -> null
        }

    private fun namedGroupOf(group: TlsSupportedGroup): String =
        when (group) {
            TlsSupportedGroup.X25519 -> "x25519"
            TlsSupportedGroup.P256 -> "secp256r1"
            TlsSupportedGroup.P384 -> "secp384r1"
            TlsSupportedGroup.P521 -> "secp521r1"
        }

    private fun applicationProtocolNameOf(protocol: TlsApplicationProtocol): String =
        when (protocol) {
            TlsApplicationProtocol.H2 -> "h2"
            TlsApplicationProtocol.HTTP_1_1 -> "http/1.1"
        }

    private fun packetBufferSize(engine: SSLEngine): Int =
        engine.session?.packetBufferSize ?: 17 * 1024

    private fun applicationBufferSize(engine: SSLEngine): Int =
        engine.session?.applicationBufferSize ?: 16 * 1024

    private fun ByteBuffer.toByteArray(): ByteArray {
        val bytes = ByteArray(remaining())
        get(bytes)
        return bytes
    }

    private fun framesOf(vararg frames: TlsChannelFrame?): TlsFrames {
        val present = frames.filterNotNull()
        return if (present.isEmpty()) emptyTlsFrames() else tlsFrames(*present.toTypedArray())
    }

    private companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)
    }
}
