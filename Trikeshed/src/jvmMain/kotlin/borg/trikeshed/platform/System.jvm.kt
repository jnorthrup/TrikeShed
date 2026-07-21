package borg.trikeshed.platform

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

actual data class ProcessResult(
    actual val exitCode: Int,
    actual val stdout: String,
    actual val stderr: String
)

/**
 * Attempts to get the program name (argv[0] equivalent) on JVM.
 * This can be tricky on the JVM. `System.getProperty("sun.java.command")`
 * often contains the main class or JAR path.
 * For robust symlink detection, a launcher script might be needed to pass
 * the invocation name as a separate system property or argument.
 */
actual fun getProgramName(): String {
    // This property usually gives the main class and its arguments, or path to JAR.
    // It's the closest common thing to an argv[0] concept without a wrapper script.
    val command = System.getProperty("sun.java.command")?.split(" ")?.firstOrNull()
    return command ?: "UnknownProgram"
}

/**
 * Gets program arguments (argv[1:] equivalent) on JVM.
 * This relies on arguments being captured from the `main` method.
 * We'll need a way to store them for access here.
 * A common pattern is to store them in a global accessible object.
 */
object MainArguments {
    var args: List<String> = emptyList()
}

actual fun getProgramArguments(): List<String> {
    return MainArguments.args
}

actual fun executeProcess(
    command: String,
    args: List<String>,
    input: String?,
    workingDir: String?
): ProcessResult {
    val commandList = mutableListOf<String>()
    commandList.add(command)
    commandList.addAll(args)

    val processBuilder = ProcessBuilder(commandList)
    workingDir?.let { processBuilder.directory(File(it)) }

    val process = processBuilder.start()

    input?.let {
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(it)
        }
    }

    // It's important to consume stdout and stderr on separate threads
    // to prevent deadlocks if either buffer fills up.
    val stdoutFuture = process.inputStream.bufferedReader().readText()
    val stderrFuture = process.errorStream.bufferedReader().readText()
    
    // Consider adding a timeout
    val exited = process.waitFor(60, TimeUnit.SECONDS) // 60 second timeout
    if (!exited) {
        process.destroyForcibly()
        return ProcessResult(-1, stdoutFuture, stderrFuture + "\nProcess timed out after 60 seconds.")
    }

    val exitCode = process.exitValue()
    return ProcessResult(exitCode, stdoutFuture, stderrFuture)
}

actual fun writeToStdOut(message: String) {
    print(message)
}

actual fun writeToStdErr(message: String) {
    System.err.print(message)
}

actual fun exitProgram(exitCode: Int) {
    kotlin.system.exitProcess(exitCode)
}
