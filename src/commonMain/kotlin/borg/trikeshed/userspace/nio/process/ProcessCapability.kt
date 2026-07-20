package borg.trikeshed.userspace.nio.process

data class ProcessCapability(
    val workerId: String,
    val allowedCommands: Set<String>,         // exact path basenames, e.g., {"echo", "ls"}
    val maxStdoutBytes: Int = 1_048_576,
    val maxStderrBytes: Int = 1_048_576,
)

class SecurityException(message: String) : RuntimeException(message)
