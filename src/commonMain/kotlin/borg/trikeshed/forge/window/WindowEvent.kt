package borg.trikeshed.forge.window

data class WindowEvent(
    val type: String,
    val payload: String,
    val timestampMillis: Long,
) {
    init { require(type.isNotBlank()) { "event type must not be blank" } }
    init { require(type.length <= 128) { "event type > 128 chars" } }
    init { require(payload.length <= 1_048_576) { "payload > 1 MiB" } }
}
