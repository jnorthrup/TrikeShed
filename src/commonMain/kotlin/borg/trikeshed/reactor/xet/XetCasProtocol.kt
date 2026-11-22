package borg.trikeshed.reactor.xet

/**
 * Request shapes of the Xet CAS read API (spec: huggingface.co/docs/xet/api). No network code and no
 * response parsing here: the term/fetch_info JSON field names were not read in full, so only the facts
 * the spec pages state are encoded.
 */
object XetCasProtocol {
    /** Recommended endpoint. On 404 or 501 clients SHOULD fall back to [reconstructionPathV1]. */
    fun reconstructionPath(fileHashHex: String): String = "/v2/reconstructions/${checked(fileHashHex)}"

    /** Deprecated v1 endpoint, still served. */
    fun reconstructionPathV1(fileHashHex: String): String = "/v1/reconstructions/${checked(fileHashHex)}"

    /** `Range` header value; [endInclusive] is inclusive. */
    fun rangeHeader(start: Long, endInclusive: Long): String {
        require(start >= 0 && endInclusive >= start)
        return "bytes=$start-$endInclusive"
    }

    private fun checked(h: String): String {
        XetHashing.fromApiHex(h)
        return h
    }
}

/** A reconstruction term: a contiguous chunk range `[chunkStart, chunkEnd)` of one xorb. */
data class XetTerm(val xorbHash: String, val chunkStart: Int, val chunkEnd: Int) {
    init {
        require(chunkStart in 0 until chunkEnd)
    }
}
