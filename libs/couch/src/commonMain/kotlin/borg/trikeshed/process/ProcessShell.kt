package borg.trikeshed.process

/**
 * Process shell for executing external commands.
 * JVM has a real implementation. Other targets throw UnsupportedOperationException.
 */
expect class ProcessShell {
    fun exec(command: CharSequence, args: List<CharSequence>): ProcessResult
    fun exec(command: CharSequence, vararg args: CharSequence): ProcessResult
}

data class ProcessResult(val exitCode: Int, val stdout: CharSequence, val stderr: CharSequence)
