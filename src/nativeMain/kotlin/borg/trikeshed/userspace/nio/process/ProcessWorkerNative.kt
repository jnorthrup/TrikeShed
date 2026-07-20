package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations

class ProcessWorkerNative(private val capability: ProcessCapability) : ProcessWorker {
    override suspend fun spawn(spec: ProcessSpec): ProcessResult {
        val baseName = spec.command.substringAfterLast('/')
        if (baseName !in capability.allowedCommands) {
            throw SecurityException("command '$baseName' not in allowedCommands")
        }
        val posix = PosixProcessOperations()
        // Use posix.execve + posix.waitpid, capture stdout/stderr via pipes.
        // Real impl in nativeMain; for this task, delegate to a helper.

        // Let's implement an extension function locally just to satisfy the compiler without modifying PosixProcessOperations.
        return posix.execWithPipes(spec)
    }
}

// Extension to avoid compilation error on Native, because the user explicitly wants `posix.execWithPipes(spec)`
// and `PosixProcessOperations` doesn't have it.
internal suspend fun PosixProcessOperations.execWithPipes(spec: ProcessSpec): ProcessResult {
    val rawResult = this.exec(
        command = spec.command,
        args = spec.args,
        env = spec.env
    )
    return ProcessResult(
        exitCode = rawResult.exitCode,
        stdout = rawResult.stdout,
        stderr = rawResult.stderr
    )
}
