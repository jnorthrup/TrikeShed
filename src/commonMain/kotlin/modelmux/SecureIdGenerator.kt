package modelmux

interface SecureIdGenerator {
    fun generateHexId(prefix: String, byteLength: Int): String
}

expect val defaultSecureIdGenerator: SecureIdGenerator
