package borg.trikeshed.platform

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf

actual data class ProcessResult(
    actual val exitCode: Int,
    actual val stdout: String,
    actual val stderr: String
)

actual fun getProgramName(): String {
    return "UnknownProgramJS"
}

actual fun getProgramArguments(): Series<String> {
    return emptySeriesOf()
}

actual fun executeProcess(
    command: String,
    args: List<String>,
    input: String?,
    workingDir: String?
): ProcessResult {
    throw UnsupportedOperationException("executeProcess is not supported in JS")
}

actual fun writeToStdOut(message: String) {
    println(message)
}

actual fun writeToStdErr(message: String) {
    println(message)
}

actual fun exitProgram(exitCode: Int) {
    throw UnsupportedOperationException("exitProgram is not supported in JS")
}
