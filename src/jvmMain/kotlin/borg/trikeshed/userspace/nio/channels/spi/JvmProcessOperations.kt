package borg.trikeshed.userspace.nio.channels.spi

import java.io.ByteArrayOutputStream

class JvmProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: CharSequence,
        args: List<CharSequence>,
        stdin: ByteArray?,
        env: Map<CharSequence, CharSequence>,
    ): ProcessResult {
        val pb = ProcessBuilder(command.toString(), *args.map { it.toString() }.toTypedArray())
        env.forEach { (k, v) -> pb.environment()[k.toString()] = v.toString() }

        val proc = pb.start()

        // Feed stdin if provided
        if (stdin != null) {
            proc.outputStream.use { it.write(stdin); it.flush() }
            proc.outputStream.close()
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
