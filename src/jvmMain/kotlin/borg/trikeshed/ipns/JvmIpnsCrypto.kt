package borg.trikeshed.ipns

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** Raw RFC8032 Ed25519; uses the existing BC dependency without changing global providers. */
class JvmIpnsCrypto : IpnsCrypto {
    override fun generate(): IpnsKeyPair = Ed25519PrivateKeyParameters(SecureRandom()).let {
        IpnsKeyPair(it.generatePublicKey().encoded, it.encoded)
    }
    override fun sign(privateKey: ByteArray, payload: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 seed must be 32 bytes" }
        return Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(privateKey, 0))
            update(payload, 0, payload.size)
            generateSignature()
        }
    }
    override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return runCatching {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(payload, 0, payload.size)
                verifySignature(signature)
            }
        }.getOrDefault(false)
    }
}
