package borg.trikeshed.tls.codec.ecdh

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * JVM X25519 via java.security + javax.crypto (Java 11+).
 */
class JvmX25519 : X25519 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = X25519.Key

    private val pubHeader = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
    private val privHeader = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20)

    override fun generateKeyPair(): X25519.KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        val pubEncoded = kp.public.encoded
        val privEncoded = kp.private.encoded
        val pubRaw = pubEncoded.copyOfRange(pubHeader.size, pubEncoded.size)
        val privRaw = privEncoded.copyOfRange(privHeader.size, privEncoded.size)
        return X25519.KeyPair(
            publicKey = pubRaw,
            privateKey = privRaw,
        )
    }

    override fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val privEncoded = privHeader + privateKey
        val pubEncoded = pubHeader + peerPublicKey
        val kf = java.security.KeyFactory.getInstance("X25519")
        val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privEncoded))
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(pubEncoded))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }
}
