package borg.trikeshed.couch.htx

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JVM actual for HMAC-SHA256 used in HTX ticket derivation.
 * Uses javax.crypto.Mac — no external dependencies needed.
 */
internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val keySpec = SecretKeySpec(key, "HmacSHA256")
    mac.init(keySpec)
    return mac.doFinal(data)
}
