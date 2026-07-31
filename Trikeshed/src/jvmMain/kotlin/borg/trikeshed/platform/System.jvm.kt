package borg.trikeshed.platform

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.seriesOf
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

actual data class ProcessResult(
    actual val exitCode: Int,
    actual val stdout: String,
    actual val stderr: String
)

actual fun getProgramName(): String {
    val command = System.getProperty("sun.java.command")?.split(" ")?.firstOrNull()
    return command ?: "UnknownProgram"
}

lateinit var jvmProgramArguments: Array<String>

object MainArguments {
    var args: List<String> = emptyList()
}

actual fun getProgramArguments(): Series<String> {
    return if (::jvmProgramArguments.isInitialized) {
        seriesOf(*jvmProgramArguments)
    } else {
        seriesOf(*MainArguments.args.toTypedArray())
    }
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

    val stdoutFuture = process.inputStream.bufferedReader().readText()
    val stderrFuture = process.errorStream.bufferedReader().readText()
    
    val exited = process.waitFor(60, TimeUnit.SECONDS)
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
