package borg.trikeshed.platform

actual data class ProcessResult(
    actual val exitCode: Int,
    actual val stdout: String,
    actual val stderr: String
)

actual fun getProgramName(): String {
    // TODO: Implement for Native. This will require platform-specific APIs
    // to access argv[0]. For example, using `kotlinx.cli.ArgParser` or
    // by passing it down from the C `main` function.
    return "UnknownProgramNative"
}

object NativeMainArguments {
    var args: List<String> = emptyList()
}

actual fun getProgramArguments(): List<String> {
    // TODO: Implement for Native. Arguments should be captured from the
    // `main` function's parameters.
    return NativeMainArguments.args
}

actual fun executeProcess(
    command: String,
    args: List<String>,
    input: String?,
    workingDir: String?
): ProcessResult {
    // TODO: Implement for Native. This will require using platform-specific
    // APIs like posix_spawn on Linux/macOS or CreateProcess on Windows.
    // For now, returning a dummy result.
    println("Warning: executeProcess is not yet implemented for Native. Command: '$command' Args: $args")
    return ProcessResult(-1, "", "Not implemented on Native platform.")
}
