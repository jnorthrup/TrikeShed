package borg.trikeshed.reactor

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
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

class JvmTlsCodecBackend @JvmOverloads constructor(
    /** Optional byte-codec engine provision: callers own in-memory identity and peer trust policy. */
    private val engineFactory: ((TlsConfig, TlsFlowState) -> SSLEngine)? = null,
    /** The SSLContexts this backend's engines come from, one per identity, with their explicit reload. */
    val contexts: JvmTlsContexts = JvmTlsContexts(),
) : TlsCodecBackend {
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

    /** An engine per connection; the identity's SSLContext is built once and shared ([contexts]). */
    private fun createEngine(config: TlsConfig, state: TlsFlowState): SSLEngine {
        engineFactory?.let { return it(config, state) }
        val peer = state.route.b
        return contexts.engine(config, state.route.a, peer.a, peer.b)
    }

    /** A client route with an empty host makes an engine without SNI or session resumption. */
    override fun recordSession(config: TlsConfig, route: TlsRoute): TlsRecordSession =
        JvmTlsRecordSession(engineFactory?.invoke(config, TlsFlowState(route))
            ?: contexts.engine(config, route.a, route.b.a.takeIf { it.isNotEmpty() }, route.b.b))

    private fun beginHandshake(handle: EngineHandle) {
        if (!handle.started) {
            handle.engine.beginHandshake()
            handle.started = true
        }
    }

    private suspend fun unwrapDownstream(
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

    private suspend fun wrapApplicationCiphertext(
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

    private suspend fun drainHandshakeCiphertext(engine: SSLEngine): ByteArray {
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

    private suspend fun runDelegatedTasks(engine: SSLEngine) {
        while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            val task = engine.delegatedTask ?: break
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                task.run()
            }
        }
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
