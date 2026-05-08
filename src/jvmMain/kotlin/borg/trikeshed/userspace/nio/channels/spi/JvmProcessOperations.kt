package borg.trikeshed.userspace.nio.channels.spi

class JvmProcessOperations : ProcessOperations {

    override fun exec(command: String, vararg args: String): ExecResult {
        val pb = ProcessBuilder(command, *args).redirectErrorStream(true)
        val proc = pb.start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        return ExecResult(exitCode, stdout, "")
    }
}
