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

    override fun compute(sharedSecret: ByteArray, helloHash: ByteArray, handshakeHash: ByteArray): TrafficKeyMaterial {
        // Early secret (no PSK)
        val es = hkdf.extract(salt = null, ikm = ByteArray(0))
        // Handshake secret
        val hs = hkdf.extract(salt = es, ikm = sharedSecret)
        // Handshake traffic secrets
        val chts = deriveSecret(hs, "c hs traffic", helloHash)
        val shts = deriveSecret(hs, "s hs traffic", helloHash)
        // Master secret
        val ms = hkdf.extract(salt = hs, ikm = ByteArray(0))
        // Application traffic secrets
        val cats = deriveSecret(ms, "c ap traffic", handshakeHash)
        val sats = deriveSecret(ms, "s ap traffic", handshakeHash)
        return TrafficKeyMaterial(
            clientHandshakeKey = trafficKey(chts),    clientHandshakeIv = trafficIv(chts),
            serverHandshakeKey = trafficKey(shts),    serverHandshakeIv = trafficIv(shts),
            clientApplicationKey = trafficKey(cats),  clientApplicationIv = trafficIv(cats),
            serverApplicationKey = trafficKey(sats),  serverApplicationIv = trafficIv(sats),
        )
    }
}
