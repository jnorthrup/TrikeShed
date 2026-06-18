package borg.trikeshed.userspace.nio.channels.spi

import java.io.ByteArrayOutputStream

class JvmProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        val pb = ProcessBuilder(command, *args.toTypedArray())
        env.forEach { (k, v) -> pb.environment()[k] = v }

        val proc = pb.start()

        // Feed stdin if provided
        proc.outputStream.use {
            if (stdin != null) {
                it.write(stdin)
                it.flush()
            }
        }

        // Read stdout
        val stdoutOut = ByteArrayOutputStream()
        proc.inputStream.use { it.copyTo(stdoutOut) }

        // Read stderr
        val stderrOut = ByteArrayOutputStream()
        if (!pb.redirectErrorStream()) {
            proc.errorStream.use { it.copyTo(stderrOut) }
        }

        val exitCode = proc.waitFor()
        return ProcessResult(exitCode, stdoutOut.toByteArray(), stderrOut.toByteArray())
    }
}
