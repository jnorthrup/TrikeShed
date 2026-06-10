package borg.trikeshed.couch.htx

/**
 * HTX block types — matches HAProxy encoding.
 * Stored in the high 4 bits of htx_blk.info.
 */
enum class HtxBlockType(val code: UByte) {
    ReqSl(0u),    // Request start-line
    ResSl(1u),    // Response start-line
    Hdr(2u),      // Header name/value
    Eoh(3u),      // End-of-headers
    Data(4u),     // Data block
    Tlr(5u),      // Trailer name/value
    Eot(6u),      // End-of-trailers
    Unused(15u),  // Unused/removed block
    // Binance HTX extended types
    DHTX_REQ(16u), // Dedicated HTX request block
    DHTX_RES(17u), // Dedicated HTX response block
    ;

    companion object {
        fun fromCode(code: UByte): HtxBlockType =
            entries.find { it.code == code } ?: Unused
    }
}
