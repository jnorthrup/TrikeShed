package borg.trikeshed.process

import java.io.File

actual class ProcessShell {
    actual fun exec(command: String, args: List<String>): ProcessResult {
        return exec(cwd = null, command = command, args = args)
    }

    actual fun exec(command: String, vararg args: String): ProcessResult =
        exec(null, command, args.toList())

    private fun exec(cwd: String?, command: String, args: List<String>): ProcessResult {
        val builder = ProcessBuilder(listOf(command) + args)
        cwd?.let { builder.directory(File(it)) }
        builder.redirectErrorStream(false)
        val process = builder.start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, stdout, stderr)
    }
}