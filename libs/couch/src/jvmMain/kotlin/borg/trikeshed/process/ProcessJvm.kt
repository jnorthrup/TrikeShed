package borg.trikeshed.process

import java.io.File

actual class ProcessShell {
    actual fun exec(command: CharSequence, args: List<CharSequence>): ProcessResult {
        return exec(cwd = null, command = command, args = args)
    }

    actual fun exec(command: CharSequence, vararg args: CharSequence): ProcessResult =
        exec(null, command, args.toList())

    private fun exec(cwd: CharSequence?, command: CharSequence, args: List<CharSequence>): ProcessResult {
        val builder = ProcessBuilder(listOf(command.toString()) + args.map { it.toString() })
        cwd?.let { builder.directory(File(it.toString())) }
        builder.redirectErrorStream(false)
        val process = builder.start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, stdout, stderr)
    }
}
