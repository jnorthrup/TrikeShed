package borg.trikeshed.userspace.nio.process

data class ProcessSpec(
    val command: String,                     // e.g., "/bin/echo"
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val timeoutMs: Long = 30_000,
) {
    init { require(command.isNotBlank()) { "command must not be blank" } }
    init { require(timeoutMs >= 0) { "timeoutMs must be >= 0" } }
}
