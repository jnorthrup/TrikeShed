package borg.trikeshed.wireproto

data class WireprotoFrame(
    val magic: Int,
    val version: Short,
    val nuid: String,
    val verb: String,
    val payload: String,
) {
    init { require(magic == MAGIC) { "bad magic" } }
    init { require(version == VERSION) { "bad version" } }
    init { require(nuid.isNotBlank()) { "nuid blank" } }
    init { require(verb.isNotBlank()) { "verb blank" } }
    init { require(nuid.length <= 65_536) { "nuid too long" } }
    init { require(verb.length <= 65_536) { "verb too long" } }
    init { require(payload.length <= MAX_PAYLOAD) { "payload > $MAX_PAYLOAD" } }

    companion object {
        const val MAGIC: Int = 0xCAFEBABE.toInt()
        const val VERSION: Short = 1
        const val MAX_PAYLOAD: Int = 65_536
    }
}
