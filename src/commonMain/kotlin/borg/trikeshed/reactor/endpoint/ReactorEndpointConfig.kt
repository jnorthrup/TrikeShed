package borg.trikeshed.reactor.endpoint

data class ReactorEndpointConfig(
    val maxPayloadBytes: Int = 65_536,
    val defaultTimeoutMs: Long = 30_000,
    val requireApproval: Boolean = false,
    val permittedVerbs: Set<String> = setOf("ping", "pong", "echo", "noop"),
) {
    init { require(maxPayloadBytes in 1..16_777_216) { "maxPayloadBytes out of range" } }
}
