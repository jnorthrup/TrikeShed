package borg.trikeshed.tls.codec.hash

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JvmSha256 : Sha256 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Sha256.Key
    override fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    override fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data)
    }
}
