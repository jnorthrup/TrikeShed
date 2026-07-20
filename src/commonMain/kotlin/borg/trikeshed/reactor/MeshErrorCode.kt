package borg.trikeshed.reactor

enum class MeshErrorCode(val code: Int) {
    PEER_NOT_FOUND(-1),
    TIMEOUT(-2),
    PAYLOAD_TOO_LARGE(-3),
    BAD_FRAME(-4),
    EMPTY_PAYLOAD(-5);

    companion object {
        fun fromInt(i: Int): MeshErrorCode? = entries.firstOrNull { it.code == i }
    }
}
