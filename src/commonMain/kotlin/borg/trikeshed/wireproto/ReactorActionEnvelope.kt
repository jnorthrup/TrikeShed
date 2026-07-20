package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.Nuid

data class ReactorActionEnvelope(
    val nuid: Nuid,
    val verb: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ReactorActionEnvelope

        if (nuid != other.nuid) return false
        if (verb != other.verb) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nuid.hashCode()
        result = 31 * result + verb.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
