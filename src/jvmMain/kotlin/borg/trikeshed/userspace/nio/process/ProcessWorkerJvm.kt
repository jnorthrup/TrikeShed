package borg.trikeshed.userspace.nio.process

import kotlinx.coroutines.async

class ProcessWorkerJvm(private val capability: ProcessCapability) : ProcessWorker {
    override suspend fun spawn(spec: ProcessSpec): ProcessResult {
        val baseName = spec.command.substringAfterLast('/')
        if (baseName !in capability.allowedCommands) {
            throw SecurityException("command '$baseName' not in allowedCommands")
        }
        val pb = ProcessBuilder(spec.command, *spec.args.toTypedArray())
        if (spec.cwd != null) pb.directory(java.io.File(spec.cwd))
        pb.environment().putAll(spec.env)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.coroutineScope {
            val proc = pb.start()
            val stdoutDeferred = this.async {
                proc.inputStream.readNBytes(capability.maxStdoutBytes)
            }
            val stderrDeferred = this.async {
                proc.errorStream.readNBytes(capability.maxStderrBytes)
            }
            val done = proc.waitFor(spec.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) {
                proc.destroyForcibly()
                throw RuntimeException("process timed out after ${spec.timeoutMs}ms")
            }
            ProcessResult(
                exitCode = proc.exitValue(),
                stdout = stdoutDeferred.await(),
                stderr = stderrDeferred.await(),
            )
        } }
    }
}
