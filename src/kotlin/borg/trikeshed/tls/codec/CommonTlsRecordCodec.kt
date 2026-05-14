package borg.trikeshed.tls.codec

import borg.trikeshed.tls.codec.aead.Aes128Gcm
import borg.trikeshed.tls.record.ContentType

/**
 * CommonMain [TlsRecordCodec] backed by context-resolved crypto.
 *
 * Constructor takes the AEAD primitive directly (laced in from context
 * by the caller). This class itself is a CCEK — register it in the
 * context and the handshake engine will resolve it.
 */
class CommonTlsRecordCodec(
    private val aes128: Aes128Gcm,
) : TlsRecordCodec {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = TlsRecordCodec.Key

    private var clientKey: ByteArray? = null
    private var clientIv: ByteArray? = null
    private var serverKey: ByteArray? = null
    private var serverIv: ByteArray? = null
    private var clientSeq: Long = 0L
    private var serverSeq: Long = 0L

    override suspend fun encrypt(direction: RecordDirection, innerType: ContentType, plaintext: ByteArray): ByteArray {
        val (key, iv, seq) = when (direction) {
            RecordDirection.CLIENT_WRITE -> Triple(clientKey!!, clientIv!!, clientSeq++)
            RecordDirection.SERVER_WRITE -> Triple(serverKey!!, serverIv!!, serverSeq++)
        }
        val nonce = xorNonce(seq, iv)
        val recordLen = plaintext.size + 1 + aes128.tagLength
        // AAD: opaque_type(0x17) || legacy_version(0x0303) || length(2)
        val aad = byteArrayOf(0x17, 0x03, 0x03, ((recordLen ushr 8) and 0xFF).toByte(), (recordLen and 0xFF).toByte())
        // Inner: plaintext || content_type
        val inner = plaintext + innerType.code
        val ct = aes128.seal(key, nonce, aad, inner)
        return aad + ct
    }

    override suspend fun decrypt(direction: RecordDirection, wire: ByteArray): ByteArray? {
        val (key, iv, seq) = when (direction) {
            RecordDirection.CLIENT_WRITE -> Triple(clientKey!!, clientIv!!, clientSeq++)
            RecordDirection.SERVER_WRITE -> Triple(serverKey!!, serverIv!!, serverSeq++)
        }
        val nonce = xorNonce(seq, iv)
        val aad = wire.copyOfRange(0, 5)
        val ct = wire.copyOfRange(5, wire.size)
        val inner = aes128.open(key, nonce, aad, ct) ?: return null
        return inner.copyOfRange(0, inner.size - 1)  // strip trailing content type
    }

    override fun installKeys(clientKey: ByteArray, clientIv: ByteArray, serverKey: ByteArray, serverIv: ByteArray) {
        this.clientKey = clientKey; this.clientIv = clientIv
        this.serverKey = serverKey; this.serverIv = serverIv
        clientSeq = 0L; serverSeq = 0L
    }

    private fun xorNonce(seq: Long, iv: ByteArray): ByteArray {
        val n = ByteArray(12)
        for (i in 0..7) n[4 + i] = ((seq ushr (56 - i * 8)) and 0xFF).toByte()
        for (i in 0..11) n[i] = (n[i].toInt() xor iv[i].toInt()).toByte()
        return n
    }
}
