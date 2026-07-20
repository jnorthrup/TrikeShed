package borg.trikeshed.reactor

sealed class MeshActionResult {
    data class Ok(val payload: ByteArray) : MeshActionResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Ok
            return payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int {
            return payload.contentHashCode()
        }
    }
    data class Failed(val code: MeshErrorCode) : MeshActionResult()
    data object TimedOut : MeshActionResult()
}
