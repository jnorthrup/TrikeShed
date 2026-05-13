package borg.trikeshed.tls

import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import borg.trikeshed.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.tls.codec.RecordDirection
import borg.trikeshed.tls.codec.TlsClientHandshake
import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.codec.ecdh.DefaultX25519
import borg.trikeshed.tls.codec.hash.DefaultSha256
import borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256
import borg.trikeshed.tls.record.ContentType

actual fun createTlsEngine(settings: TlsSettings): TlsEngine {
    val aes = DefaultAes128Gcm()
    val codec = CommonTlsRecordCodec(aes)
    val sha256 = DefaultSha256()
    val hkdf = DefaultHkdfSha256(sha256)
    val handshake = CommonTlsClientHandshake(
        sha256 = sha256,
        x25519 = DefaultX25519(),
        hkdf = hkdf,
        recordCodec = codec,
        serverName = settings.serverName ?: "localhost",
    )
    return NativeTlsEngine(codec, handshake, settings)
}

private class NativeTlsEngine(
    private val codec: CommonTlsRecordCodec,
    private val handshake: CommonTlsClientHandshake,
    private val settings: TlsSettings,
) : TlsEngine {

    private var connected = false

    /**
     * Drive the complete TLS 1.3 handshake with the remote server.
     *
     * [reader] returns one TLS record at a time (5-byte header stripped).
     * [writer] sends a raw TLS record (including the 5-byte TLS record header).
     */
    suspend fun handshake(
        reader: suspend () -> ByteArray,
        writer: suspend (ByteArray) -> Unit,
    ) {
        check(!connected) { "already connected" }

        // 1. Send ClientHello
        val ch = handshake.buildClientHello()
        writer(ch)

        // 2. Read ServerHello
        val sh = reader()
        handshake.processServerHello(sh)
        if (handshake.state == TlsClientHandshake.State.WAITING_EE) {
            val ee = reader()
            handshake.processEncryptedExtensions(ee)
        }
        if (handshake.state == TlsClientHandshake.State.WAITING_CERT) {
            val cert = reader()
            handshake.processCertificate(cert)
        }
        if (handshake.state == TlsClientHandshake.State.WAITING_CV) {
            val cv = reader()
            handshake.processCertificateVerify(cv)
        }
        if (handshake.state == TlsClientHandshake.State.WAITING_FINISHED) {
            val sf = reader()
            handshake.processServerFinished(sf)
        }

        // 7. Send ClientFinished
        val cf = handshake.buildClientFinished()
        writer(cf)

        // 8. Install application traffic keys
        handshake.installApplicationKeys()
        connected = true
    }

    override suspend fun wrap(plain: ByteArray): ByteArray {
        check(connected) { "handshake() not called" }
        return codec.encrypt(
            RecordDirection.CLIENT_WRITE,
            ContentType.APPLICATION_DATA,
            plain,
        )
    }

    override suspend fun unwrap(encrypted: ByteArray): ByteArray =
        codec.decrypt(
            RecordDirection.SERVER_WRITE,
            encrypted,
        )
            ?: error("TLS decrypt failed")

    override suspend fun close() {
        connected = false
    }
}
