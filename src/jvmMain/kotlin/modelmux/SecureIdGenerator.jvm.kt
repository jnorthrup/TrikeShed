package modelmux

import java.security.SecureRandom

actual val defaultSecureIdGenerator: SecureIdGenerator = object : SecureIdGenerator {
    private val random = SecureRandom()

    override fun generateHexId(prefix: String, byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "$prefix-$hex"
    }
}
