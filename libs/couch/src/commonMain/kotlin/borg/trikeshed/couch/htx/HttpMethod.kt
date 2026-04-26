package borg.trikeshed.couch.htx

/**
 * HTTP methods — used in HTX request start-lines.
 */
enum class HttpMethod {
    Get, Post, Put, Delete, Head, Options, Connect, Patch, Trace, Unknown;

    companion object {
        private val BY_NAME = entries.associateBy { it.name.uppercase() }

        fun fromBytes(b: ByteArray): HttpMethod? {
            val s = b.decodeToString()
            return BY_NAME[s]
        }

        fun fromString(s: String): HttpMethod? = BY_NAME[s]
    }

    fun toBytes(): ByteArray = when (this) {
        Unknown -> byteArrayOf()
        else -> name.uppercase().encodeToByteArray()
    }
}
