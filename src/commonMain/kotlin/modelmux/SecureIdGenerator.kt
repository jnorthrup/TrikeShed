package modelmux

import borg.trikeshed.userspace.nio.platform.spi.*
import borg.trikeshed.util.*

interface SecureIdGenerator {
    fun generateHexId(prefix: String, byteLength: Int): String
}

val defaultSecureIdGenerator: SecureIdGenerator = object : SecureIdGenerator {
    override fun generateHexId(prefix: String, byteLength: Int): String =
        "$prefix-" + ByteArray(byteLength).also(::platformGetRandom).toLowerHex()
}
