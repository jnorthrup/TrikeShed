package borg.trikeshed.platform

/**
 * Represents the result of an executed process.
 *
 * @property exitCode The exit code of the process.
 * @property stdout The standard output of the process as a String.
 * @property stderr The standard error of the process as a String.
 */
data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Gets the name of the currently running program (argv[0]).
 * This can vary depending on how the program was launched (e.g., direct execution, symlink).
 */
expect fun getProgramName(): String

/**
 * Gets the arguments passed to the currently running program (argv[1:]).
 */
expect fun getProgramArguments(): List<String>

/**
 * Executes an external process.
 *
 * @param command The command or path to the executable.
 * @param args A list of arguments to pass to the command.
 * @param input An optional string to pass to the process's standard input.
 * @param workingDir An optional working directory for the process. If null, inherits from the current process.
 * @return A [ProcessResult] containing the exit code, stdout, and stderr of the executed process.
 */
expect fun executeProcess(
    command: String,
    args: List<String> = emptyList(),
    input: String? = null,
    workingDir: String? = null
): ProcessResult

/**
 * Writes a message to the standard output stream.
 * A newline is typically NOT automatically appended.
 */
expect fun writeToStdOut(message: String)

/**
 * Writes a message to the standard error stream.
 * A newline is typically NOT automatically appended.
 */
expect fun writeToStdErr(message: String)

/**
 * Exits the current program with the given status code.
 */
expect fun exitProgram(exitCode: Int)
