package borg.trikeshed.tls.codec.ecdh

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * JVM X25519 via java.security + javax.crypto (Java 11+).
 */
class JvmX25519 : borg.trikeshed.userspace.nio.tls.codec.ecdh.X25519 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = _root_ide_package_.borg.trikeshed.userspace.nio.tls.codec.ecdh.X25519.Key

    override fun generateKeyPair(): X25519.KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        return X25519.KeyPair(
            publicKey = kp.public.encoded,
            privateKey = kp.private.encoded,
        )
    }

    override fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val kf = java.security.KeyFactory.getInstance("X25519")
        val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKey))
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(peerPublicKey))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }
}
