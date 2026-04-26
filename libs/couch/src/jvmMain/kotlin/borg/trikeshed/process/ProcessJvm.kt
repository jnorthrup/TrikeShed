package borg.trikeshed.process

actual class ProcessShell {
    actual fun exec(command: String, args: List<String>): ProcessResult {
        val process = ProcessBuilder(command, *args.toTypedArray())
            .redirectErrorStream(true)
            .start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, stdout, "")
    }
    actual fun exec(command: String, vararg args: String) = exec(command, args.toList())
}
