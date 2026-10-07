package borg.trikeshed.forge.server

data class HttpForwarderResponse(
    val status: Int,            // 200 / 400 / 500 / 502 / 504
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    init {
        require(status in 100..599) { "status must be between 100 and 599" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as HttpForwarderResponse
        if (status != other.status) return false
        if (headers != other.headers) return false
        if (!body.contentEquals(other.body)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}
