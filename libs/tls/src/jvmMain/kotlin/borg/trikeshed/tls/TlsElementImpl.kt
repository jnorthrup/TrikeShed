package borg.trikeshed.tls

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManagerFactory
import kotlin.coroutines.CoroutineContext

@Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")
internal class TlsEndpointImpl(
    private val config: TlsConfig,
    private val engine: SSLEngine,
    private val channelRunner: ChannelRunner,
    private val fd: Int,
) : TlsEndpoint {

    private val appBufferSize = 16 * 1024
    private val netBufferSize = 17 * 1024

    private var appReadBuffer: ByteBuffer? = null
    private var appWriteBuffer: ByteBuffer? = null

    sealed class HandshakeState {
        data object NotStarted : HandshakeState()
        data object InProgress : HandshakeState()
        data object Completed : HandshakeState()
        data class Failed(val error: Throwable) : HandshakeState()
    }

    private var handshakeState: HandshakeState = HandshakeState.NotStarted

    override suspend fun handshake() {
        if (handshakeState != HandshakeState.NotStarted) {
            return
        }

        handshakeState = HandshakeState.InProgress

        try {
            engine.beginHandshake()

            var status = engine.handshakeStatus
            while (status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    engine.delegatedTask?.run()
                }
                if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    doWrap()
                    channelRunner.writeAsync(fd)
                }
                if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                    if (appReadBuffer == null || !appReadBuffer!!.hasRemaining()) {
                        channelRunner.readAsync(fd)
                    }
                    doUnwrap()
                }

                status = engine.handshakeStatus
            }

            handshakeState = HandshakeState.Completed
        } catch (e: Exception) {
            handshakeState = HandshakeState.Failed(e)
            throw e
        }
    }

    override suspend fun read(destination: ByteBuffer): Int {
        check(handshakeState == HandshakeState.Completed) { "Handshake not completed" }

        while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            if (appReadBuffer != null && appReadBuffer!!.hasRemaining()) {
                val appBuf = appReadBuffer!!
                val pos = appBuf.position()
                destination.put(appBuf)
                return destination.position() - pos
            }

            channelRunner.readAsync(fd)
            val consumed = doUnwrap()
            if (consumed <= 0) {
                return -1
            }
        }

        return -1
    }

    override suspend fun write(source: ByteBuffer): Int {
        check(handshakeState == HandshakeState.Completed) { "Handshake not completed" }

        doWrap(source)
        channelRunner.writeAsync(fd)

        return source.remaining()
    }

    override suspend fun close() {
        engine.closeOutbound()

        while (!engine.isOutboundDone) {
            doWrap()
            channelRunner.writeAsync(fd)
        }
    }

    private fun doWrap(appData: ByteBuffer? = null): Int {
        val source = appData ?: ByteBuffer.allocate(appBufferSize)
        val dest = ByteBuffer.allocate(netBufferSize)

        val result = engine.wrap(source, dest)
        dest.flip()

        if (dest.hasRemaining()) {
            val encrypted = ByteArray(dest.remaining())
            dest.get(encrypted)
            appWriteBuffer = dest
        }

        return result.bytesProduced()
    }

    private fun doUnwrap(): Int {
        val source = ByteBuffer.allocate(netBufferSize)
        val dest = ByteBuffer.allocate(appBufferSize)

        // In real implementation, read from socket into source
        source.flip()

        if (!source.hasRemaining()) {
            return 0
        }

        val result = engine.unwrap(source, dest)
        dest.flip()

        if (dest.hasRemaining()) {
            appReadBuffer = dest
        }

        return result.bytesConsumed()
    }

    override val isHandshakeComplete: Boolean
        get() = handshakeState == HandshakeState.Completed

    override val peerCertificate: X509Certificate?
        get() {
            val session = engine.session
            return session?.peerCertificateChain?.firstOrNull() as? X509Certificate
        }

    override val protocol: String?
        get() = engine.session?.protocol

    override val cipherSuite: String?
        get() = engine.session?.cipherSuite
}

@Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")
class TlsElementImpl(
    private val config: TlsConfig,
    private val channelRunner: ChannelRunner,
) : TlsElement {

    private val parentJob: Job? = null
    private val supervisor: CompletableJob = SupervisorJob(parentJob)

    override val key: CoroutineContext.Key<*> get() = TlsKey

    override var state: ElementState = ElementState.CREATED
        protected set

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            state = ElementState.OPEN
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            if (state < ElementState.DRAINING) {
                state = ElementState.DRAINING
            }
            supervisor.cancel()
            state = ElementState.CLOSED
        }
    }

    override fun clientEndpoint(hostname: String): TlsEndpoint {
        val engine = createSSLEngine(config, isServer = false, hostname = hostname)
        return TlsEndpointImpl(config, engine, channelRunner, fd = 0)
    }

    override fun serverEndpoint(): TlsEndpoint {
        val engine = createSSLEngine(config, isServer = true)
        return TlsEndpointImpl(config, engine, channelRunner, fd = 0)
    }

    private fun createSSLEngine(config: TlsConfig, isServer: Boolean, hostname: String = "localhost"): SSLEngine {
        val sslContext = createSSLContext(config)
        return if (isServer) {
            sslContext.createSSLEngine()
        } else {
            sslContext.createSSLEngine(hostname, 443)
        }
    }

    private fun createSSLContext(config: TlsConfig): SSLContext {
        val context = SSLContext.getInstance("TLS")

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        if (config.keyStore != null) {
            val password = config.keyStorePassword?.toCharArray() ?: CharArray(0)
            java.io.FileInputStream(config.keyStore).use { fis ->
                keyStore.load(fis, password)
            }
        } else {
            keyStore.load(null, null)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val keyPassword = config.keyStorePassword?.toCharArray() ?: CharArray(0)
        kmf.init(keyStore, keyPassword)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (config.trustStore != null) {
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
            val trustPassword = config.trustStorePassword?.toCharArray() ?: CharArray(0)
            java.io.FileInputStream(config.trustStore).use { fis ->
                trustStore.load(fis, trustPassword)
            }
            tmf.init(trustStore)
        } else {
            tmf.init(keyStore)
        }

        context.init(kmf.keyManagers, tmf.trustManagers, null)
        return context
    }
}

/**
 * JVM-specific function to create and open a TLS element with a ChannelRunner.
 */
@kotlin.jvm.JvmName("openTlsElementWithRunner")
actual suspend fun openTlsElementWithRunner(
    config: TlsConfig,
    channelRunner: ChannelRunner,
): TlsElement {
    val element = TlsElementImpl(config, channelRunner)
    element.open()
    return element
}