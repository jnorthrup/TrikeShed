package borg.trikeshed.couch.htx

import borg.trikeshed.context.BitMasked

/**
 * HTX start-line flags.
 */
enum class HtxSlFlags : BitMasked {
    IS_RESP,
    XFER_LEN,
    XFER_ENC,
    CLEN,
    CHNK,
    VER_11,
    BODYLESS,
    HAS_SCHM,
    SCHM_HTTP,
    SCHM_HTTPS,
    HAS_AUTHORITY,
    NORMALIZED_URI,
    CONN_UPG,
    BODYLESS_RESP,
    NOT_HTTP;

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
enum class HtxFlags : BitMasked {
    NONE,
    PARSING_ERROR,
    PROCESSING_ERROR,
    FRAGMENTED,
    UNORDERED,
    EOM;

    override val mask: UInt get() = if (this == NONE) 0u else 1u shl (ordinal - 1)

    companion object {
        fun fromMask(mask: UInt): Set<HtxFlags> =
            entries.filter { (mask and it.mask) != 0u }.toSet()

        fun toMask(flags: Iterable<HtxFlags>): UInt =
            flags.fold(0u) { acc, flag -> acc or flag.mask }
    }
}
