package borg.trikeshed.treedoc

/**
 * Represents a document to be archived.
 */
data class TreeDocument(
    val path: String,
    val mediaType: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as TreeDocument

        if (path != other.path) return false
        if (mediaType != other.mediaType) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
