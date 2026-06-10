package borg.trikeshed.process

/**
 * Process shell for executing external commands.
 * JVM has a real implementation. Other targets throw UnsupportedOperationException.
 */
expect class ProcessShell {
    fun exec(command: String, args: List<String>): ProcessResult
    fun exec(command: String, vararg args: String): ProcessResult
}

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)
