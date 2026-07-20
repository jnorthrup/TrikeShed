package borg.trikeshed.wireproto

class WireprotoFormatException(message: String) : RuntimeException(message) {
    companion object {
        const val BAD_MAGIC = "bad magic 0x"
        const val BAD_VERSION = "bad version "
        const val TRUNCATED = "truncated frame: expected "
        const val OVERSIZE_PAYLOAD = "payload > 65536 bytes"
        const val BAD_NUID_LENGTH = "bad nuid length: "
        const val BAD_VERB_LENGTH = "bad verb length: "
        const val BAD_PAYLOAD_LENGTH = "bad payload length: "
    }
}
