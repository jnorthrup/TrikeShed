package borg.trikeshed.userspace.htx

class HtxSlFlags(val bits: UInt) {
    companion object {
        val IS_RESP: UInt        = 0x00000001u
        val XFER_LEN: UInt       = 0x00000002u
        val XFER_ENC: UInt       = 0x00000004u
        val CLEN: UInt           = 0x00000008u
        val CHNK: UInt           = 0x00000010u
        val VER_11: UInt         = 0x00000020u
        val BODYLESS: UInt       = 0x00000040u
        val HAS_SCHM: UInt       = 0x00000080u
        val SCHM_HTTP: UInt      = 0x00000100u
        val SCHM_HTTPS: UInt     = 0x00000200u
        val HAS_AUTHORITY: UInt  = 0x00000400u
        val NORMALIZED_URI: UInt = 0x00000800u
        val CONN_UPG: UInt       = 0x00001000u
        val BODYLESS_RESP: UInt  = 0x00002000u
        val NOT_HTTP: UInt       = 0x00004000u
    }
}

/**
 * HTX message flags.
 */
class HtxFlags(val bits: UInt) {
    companion object {
        val NONE: UInt             = 0x00000000u
        val PARSING_ERROR: UInt    = 0x00000001u
        val PROCESSING_ERROR: UInt = 0x00000002u
        val FRAGMENTED: UInt       = 0x00000004u
        val UNORDERED: UInt        = 0x00000008u
        val EOM: UInt              = 0x00000010u  // End of message
    }
}
