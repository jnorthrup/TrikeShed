package borg.trikeshed.userspace.htx

/**
 * HTX start-line flags.
 */
enum class HtxSlFlags(val bit: Int) {
    IS_RESP(0),
    XFER_LEN(1),
    XFER_ENC(2),
    CLEN(3),
    CHNK(4),
    VER_11(5),
    BODYLESS(6),
    HAS_SCHM(7),
    SCHM_HTTP(8),
    SCHM_HTTPS(9),
    HAS_AUTHORITY(10),
    NORMALIZED_URI(11),
    CONN_UPG(12),
    BODYLESS_RESP(13),
    NOT_HTTP(14);

    val mask: UInt get() = 1u shl bit

    companion object {
        fun fromMask(mask: UInt): Set<HtxSlFlags> =
            entries.filter { (mask and it.mask) != 0u }.toSet()

        fun toMask(flags: Iterable<HtxSlFlags>): UInt =
            flags.fold(0u) { acc, flag -> acc or flag.mask }
    }
}

/**
 * HTX message flags.
 */
enum class HtxFlags(val bit: Int) {
    PARSING_ERROR(0),
    PROCESSING_ERROR(1),
    FRAGMENTED(2),
    UNORDERED(3),
    EOM(4); // End of message

    val mask: UInt get() = 1u shl bit

    companion object {
        val NONE: UInt = 0u

        fun fromMask(mask: UInt): Set<HtxFlags> =
            entries.filter { (mask and it.mask) != 0u }.toSet()

        fun toMask(flags: Iterable<HtxFlags>): UInt =
            flags.fold(0u) { acc, flag -> acc or flag.mask }
    }
}
