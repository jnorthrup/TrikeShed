package borg.trikeshed.reactor

data class MeshConfig(
    val timeoutMs: Long = 5_000,
    val maxRetries: Int = 3,
    val maxPayloadBytes: Int = 65_536,
) {
    init {
        require(timeoutMs >= 0) { "timeoutMs must be >= 0" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(maxPayloadBytes in 1..16_777_216) { "maxPayloadBytes out of range" }
    }
}
