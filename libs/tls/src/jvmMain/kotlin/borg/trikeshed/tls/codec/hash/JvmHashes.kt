package borg.trikeshed.tls.codec.hash

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JVM SHA-256 via java.security.MessageDigest.
 */
class JvmSha256 : Sha256 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Sha256.Key

    override fun hash(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    override fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

class JvmSha384 : Sha384 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Sha384.Key

    override fun hash(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-384")
        return md.digest(data)
    }

    override fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA384")
        mac.init(SecretKeySpec(key, "HmacSHA384"))
        return mac.doFinal(data)
    }
}
