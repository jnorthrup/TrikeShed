package borg.trikeshed.cas

import borg.trikeshed.job.ContentId

data class CasManifest(
    val cids: List<ContentId>,    // sorted lexicographically
    val metadata: ByteArray = ByteArray(0),
) {
    fun contentId(): ContentId {
        val sb = StringBuilder()
        sb.append("MANIFEST1\n")
        for (c in cids) sb.append(c.value).append('\n')
        sb.append("META\n")
        sb.append(metadata.size).append('\n')
        sb.append(metadata.decodeToString())
        return ContentId.of((sb.toString().length.toString() + ":" + sb.toString()).encodeToByteArray())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CasManifest) return false
        return cids == other.cids && metadata.contentEquals(other.metadata)
    }

    override fun hashCode(): Int = cids.hashCode() * 31 + metadata.contentHashCode()
}
