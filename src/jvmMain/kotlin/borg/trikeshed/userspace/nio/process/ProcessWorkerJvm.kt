package borg.trikeshed.userspace.nio.process

class ProcessWorkerJvm(private val capability: ProcessCapability) : ProcessWorker {
    override suspend fun spawn(spec: ProcessSpec): ProcessResult {
        val baseName = spec.command.substringAfterLast('/')
        if (baseName !in capability.allowedCommands) {
            throw SecurityException("command '$baseName' not in allowedCommands")
        }
        val pb = ProcessBuilder(spec.command, *spec.args.toTypedArray())
        if (spec.cwd != null) pb.directory(java.io.File(spec.cwd))
        pb.environment().putAll(spec.env)
        val proc = pb.start()
        val done = proc.waitFor(spec.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!done) {
            proc.destroyForcibly()
            throw RuntimeException("process timed out after ${spec.timeoutMs}ms")
        }
        return ProcessResult(
            exitCode = proc.exitValue(),
            stdout = proc.inputStream.readNBytes(capability.maxStdoutBytes),
            stderr = proc.errorStream.readNBytes(capability.maxStderrBytes),
        )
    }
}
