package borg.trikeshed.tls.codec

import borg.trikeshed.tls.codec.kdf.HkdfSha256

/**
 * CommonMain [TlsKeySchedule] backed by context-resolved HKDF.
 *
 * Implements the full RFC 8446 §7.1 three-phase key schedule
 * using the injected [HkdfSha256] CCEK.
 */
class CommonTlsKeySchedule(
    private val hkdf: HkdfSha256,
) : TlsKeySchedule {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = TlsKeySchedule.Key

    private fun deriveSecret(secret: ByteArray, label: String, messagesHash: ByteArray): ByteArray =
        hkdf.expandLabel(secret, label, messagesHash, 32)

    private fun trafficKey(secret: ByteArray): ByteArray =
        hkdf.expandLabel(secret, "key", ByteArray(0), keyLength)

    private fun trafficIv(secret: ByteArray): ByteArray =
        hkdf.expandLabel(secret, "iv", ByteArray(0), ivLength)

    private val emptyHash = byteArrayOf(
        0xe3.toByte(), 0xb0.toByte(), 0xc4.toByte(), 0x42.toByte(),
        0x98.toByte(), 0xfc.toByte(), 0x1c.toByte(), 0x14.toByte(),
        0x9a.toByte(), 0xfb.toByte(), 0xf4.toByte(), 0xc8.toByte(),
        0x99.toByte(), 0x6f.toByte(), 0xb9.toByte(), 0x24.toByte(),
        0x27.toByte(), 0xae.toByte(), 0x41.toByte(), 0xe4.toByte(),
        0x64.toByte(), 0x9b.toByte(), 0x93.toByte(), 0x4c.toByte(),
        0xa4.toByte(), 0x95.toByte(), 0x99.toByte(), 0x1b.toByte(),
        0x78.toByte(), 0x52.toByte(), 0xb8.toByte(), 0x55.toByte()
    )

    override fun compute(sharedSecret: ByteArray, helloHash: ByteArray, handshakeHash: ByteArray): TrafficKeyMaterial {
        val zeros = ByteArray(32)
        // Early secret (no PSK)
        val es = hkdf.extract(salt = null, ikm = zeros)
        // derived early secret
        val derivedEs = deriveSecret(es, "derived", emptyHash)
        // Handshake secret
        val hs = hkdf.extract(salt = derivedEs, ikm = sharedSecret)
        // Handshake traffic secrets
        val chts = deriveSecret(hs, "c hs traffic", helloHash)
        val shts = deriveSecret(hs, "s hs traffic", helloHash)
        // derived handshake secret
        val derivedHs = deriveSecret(hs, "derived", emptyHash)
        // Master secret
        val ms = hkdf.extract(salt = derivedHs, ikm = zeros)
        // Application traffic secrets
        val cats = deriveSecret(ms, "c ap traffic", handshakeHash)
        val sats = deriveSecret(ms, "s ap traffic", handshakeHash)
        return TrafficKeyMaterial(
            clientHandshakeKey = trafficKey(chts),    clientHandshakeIv = trafficIv(chts),
            serverHandshakeKey = trafficKey(shts),    serverHandshakeIv = trafficIv(shts),
            clientApplicationKey = trafficKey(cats),  clientApplicationIv = trafficIv(cats),
            serverApplicationKey = trafficKey(sats),  serverApplicationIv = trafficIv(sats),
            clientHandshakeSecret = chts,
            serverHandshakeSecret = shts,
        )
    }
}
