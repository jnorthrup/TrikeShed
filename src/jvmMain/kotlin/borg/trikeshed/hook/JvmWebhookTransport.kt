package borg.trikeshed.hook

import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.jules.TrikeHtxHttpClient
import borg.trikeshed.lib.j
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** JVM HmacSHA256 implementation shared by inbound verification and outbound signing. */
object JvmHookSigner : HookSigner {
    override fun sign(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        val bytes = mac.doFinal(body.encodeToByteArray())
        return buildString(bytes.size * 2) {
            for (b in bytes) append((b.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }

    fun verifies(secret: String, body: String, signature: String): Boolean {
        val expected = sign(secret, body).encodeToByteArray()
        return java.security.MessageDigest.isEqual(expected, signature.lowercase().encodeToByteArray())
    }
}

/** userspace.nio/HTX outbound sender — no JDK HTTP client or socket. */
object HtxHookSender : HookSender {
    override suspend fun post(targetUrl: String, body: String, nuid: String, signature: String) {
        val schemeEnd = targetUrl.indexOf("://")
        require(schemeEnd > 0) { "absolute hook target required" }
        val pathStart = targetUrl.indexOf('/', schemeEnd + 3)
        val base = if (pathStart < 0) targetUrl else targetUrl.substring(0, pathStart)
        val path = if (pathStart < 0) "/" else targetUrl.substring(pathStart)
        TrikeHtxHttpClient(
            base,
            htxHeaders(
                "X-Delivery-NUID" j nuid,
                "X-TrikeShed-Signature" j signature,
            ),
        ).post(path, body)
    }
}
