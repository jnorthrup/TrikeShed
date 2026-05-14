package borg.trikeshed.userspace.nio.tls

import borg.trikeshed.lib.*
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Real JVM TLS engine backed by javax.net.ssl.SSLEngine.
 *
 * Uses JDK's built-in TLS 1.2/1.3 implementation (no external dependencies).
 * Operates in client mode with SNI support and configurable cipher suites.
 */
class TlsEngineJdk(private val settings: borg.trikeshed.userspace.nio.tls.TlsSettings) :
    borg.trikeshed.userspace.nio.tls.TlsEngine {

    private val sslContext: SSLContext = SSLContext.getInstance(
        when (settings.protocolVersion) {
            _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsProtocolVersion.V1_3 -> "TLSv1.3"
            _root_ide_package_.borg.trikeshed.userspace.nio.tls.TlsProtocolVersion.V1_2 -> "TLSv1.2"
        }
    ).also { it.init(null, null, null) }

    private val sslEngine: SSLEngine = sslContext.createSSLEngine().apply {
        useClientMode = true
        settings.serverName?.let { sni ->
            sslParameters = sslParameters.also { it.serverNames = listOf(javax.net.ssl.SNIHostName(sni.cs.encodeToByteArray())) }
        }
        if (settings.cipherSuites.isNotEmpty()) {
            enabledCipherSuites =( settings.cipherSuites α { it.toString() }).toArray()
        }
    }

    // 16 KiB buffers — typical TLS record size
    private val appIn = ByteBuffer.allocate(16384)
    private val appOut = ByteBuffer.allocate(16384)
    private val netIn = ByteBuffer.allocate(32768)
    private val netOut = ByteBuffer.allocate(32768)

    private var handshakeDone = false

    /**
     * Wrap plaintext → TLS record bytes.
     */
    override suspend fun wrap(plain: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        handshakeIfNeeded()

        appIn.clear()
        appIn.put(plain)
        appIn.flip()
        netOut.clear()

        val result = sslEngine.wrap(appIn, netOut)
        check(result.status != SSLEngineResult.Status.BUFFER_OVERFLOW) { "TLS output buffer overflow" }

        netOut.flip()
        val out = ByteArray(netOut.remaining())
        netOut.get(out)
        out
    }

    /**
     * Unwrap TLS record bytes → plaintext.
     */
    override suspend fun unwrap(encrypted: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        handshakeIfNeeded()

        netIn.clear()
        netIn.put(encrypted)
        netIn.flip()
        appOut.clear()

        val result = sslEngine.unwrap(netIn, appOut)
        when (result.status) {
            SSLEngineResult.Status.OK -> {}
            SSLEngineResult.Status.CLOSED -> throw IllegalStateException("TLS connection closed by peer")
            SSLEngineResult.Status.BUFFER_OVERFLOW -> throw IllegalStateException("TLS input buffer overflow")
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {} // need more data — return what we have
        }

        appOut.flip()
        val out = ByteArray(appOut.remaining())
        appOut.get(out)
        out
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            sslEngine.closeOutbound()
            sslEngine.closeInbound()
        }
    }

    private fun handshakeIfNeeded() {
        if (handshakeDone) return
        try {
            sslEngine.beginHandshake()
            handshakeDone = true
        } catch (e: Exception) {
            throw IllegalStateException("TLS handshake failed: ${e.message}", e)
        }
    }
}
