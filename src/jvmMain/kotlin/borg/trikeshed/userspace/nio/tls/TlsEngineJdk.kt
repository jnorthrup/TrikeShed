package borg.trikeshed.userspace.nio.tls

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.userspace.nio.tls.codec.CommonTlsRecordCodec
import borg.trikeshed.userspace.nio.tls.codec.RecordDirection
import borg.trikeshed.userspace.nio.tls.codec.aead.Aes128Gcm
import borg.trikeshed.userspace.nio.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.userspace.nio.tls.codec.ecdh.DefaultX25519
import borg.trikeshed.userspace.nio.tls.codec.hash.DefaultSha256
import borg.trikeshed.userspace.nio.tls.codec.kdf.DefaultHkdfSha256
import kotlinx.coroutines.Job

/**
 * JVM actual of [TlsEngine] backed entirely by commonMain TLS primitives.
 * No javax.net.ssl. No java.security. Pure commonMain.
 *
 * CCEK-compliant: extends AsyncContextElement with lifecycle.
 */
class TlsEngineJdk(
    private val settings: TlsSettings,
    parentJob: Job? = null,
) : AsyncContextElement(parentJob = parentJob), TlsEngine {

    companion object Key : AsyncContextKey<TlsEngineJdk>()
    override val key get() = Key

    private val sha256 = DefaultSha256()
    private val x25519 = DefaultX25519()
    private val hkdf = DefaultHkdfSha256(sha256)
    private val aes = DefaultAes128Gcm()

    private val codec: CommonTlsRecordCodec = CommonTlsRecordCodec(aes)
    private val clientHandshake: CommonTlsClientHandshake = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, settings.serverName ?: "")
    private var connected = false

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            // Release TLS state
            connected = false
            super.close()
            state = ElementState.CLOSED
        }
    }

    override suspend fun wrap(plain: ByteArray): ByteArray {
        check(connected) { "TLS not connected yet" }
        return codec.encrypt(
            RecordDirection.CLIENT_WRITE,
            borg.trikeshed.userspace.nio.tls.record.ContentType.APPLICATION_DATA,
            plain
        )
    }

    override suspend fun unwrap(encrypted: ByteArray): ByteArray {
        check(connected) { "TLS not connected yet" }
        return codec.decrypt(RecordDirection.SERVER_WRITE, encrypted)
            ?: error("TLS decrypt failed")
    }

    override suspend fun handshake(
        reader: suspend () -> ByteArray,
        writer: suspend (ByteArray) -> Unit,
    ) {
        if (connected) return

        // Send ClientHello
        val clientHello = clientHandshake.buildClientHello()
        writer(
            byteArrayOf(0x16, 0x03, 0x03,
                ((clientHello.size ushr 8) and 0xFF).toByte(),
                (clientHello.size and 0xFF).toByte()
            ) + clientHello
        )

        var done = false
        while (!done) {
            val raw = reader()
            val n = raw.size
            var p = 0
            while (p + 5 <= n) {
                val contentType = raw[p].toInt() and 0xFF
                val rl = ((raw[p + 3].toInt() and 0xFF) shl 8) or (raw[p + 4].toInt() and 0xFF)
                if (p + 5 + rl > n) break
                val payload = raw.copyOfRange(p + 5, p + 5 + rl)
                val rec = raw.copyOfRange(p, p + 5 + rl)
                p += 5 + rl

                when (contentType) {
                    0x16 -> { // Handshake
                        when (clientHandshake.state.name) {
                            "CLIENT_HELLO_SENT" -> clientHandshake.processServerHello(payload)
                            "WAITING_EE" -> clientHandshake.processEncryptedExtensions(payload)
                            "WAITING_CERT" -> clientHandshake.processCertificate(payload)
                            "WAITING_CV" -> clientHandshake.processCertificateVerify(payload)
                            "WAITING_FINISHED" -> {
                                clientHandshake.processServerFinished(payload)
                                val finished = clientHandshake.buildClientFinished()
                                val enc = codec.encrypt(
                                    RecordDirection.CLIENT_WRITE,
                                    borg.trikeshed.userspace.nio.tls.record.ContentType.HANDSHAKE,
                                    finished
                                )
                                writer(enc)
                            }
                        }
                        if (clientHandshake.state.name == "CONNECTED") {
                            done = true
                        }
                    }
                    0x17 -> { // Application data during handshake
                        codec.decrypt(RecordDirection.SERVER_WRITE, rec)?.let { d ->
                            processHsMessage(d)
                            if (clientHandshake.state.name == "CONNECTED") done = true
                        }
                    }
                }
            }
        }

        connected = true
    }

    private fun processHsMessage(data: ByteArray) {
        val msgType = data[0].toInt() and 0xFF
        when (msgType) {
            0x08 -> clientHandshake.processEncryptedExtensions(data)
            0x0B -> clientHandshake.processCertificate(data)
            0x0F -> clientHandshake.processCertificateVerify(data)
            0x14 -> clientHandshake.processServerFinished(data)
        }
    }
}