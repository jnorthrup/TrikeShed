package borg.trikeshed.util.oroboros

/**
 * Represents a mutation intent to the coordinator.
 */
sealed class Mutation {
    abstract val path: String

    data class Upsert(
        override val path: String,
        val bytes: ByteArray,
        val contentType: String = "application/octet-stream"
    ) : Mutation() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Upsert

            if (path != other.path) return false
            if (contentType != other.contentType) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + contentType.hashCode()
            return result
        }
    }

    data class Delete(
        override val path: String
    ) : Mutation()
}
