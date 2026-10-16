package borg.trikeshed.userspace.nio.process

import borg.trikeshed.graal.subvm.GuestEnvironment
import kotlinx.coroutines.async

class ProcessWorkerJvm(private val capability: ProcessCapability) : ProcessWorker {
    override suspend fun spawn(spec: ProcessSpec): ProcessResult {
        val basename = spec.command.substringAfterLast('/')
        if (basename !in capability.allowedCommands) {
            throw SecurityException("command '$basename' not in allowedCommands")
        }
        val pb = ProcessBuilder(spec.command, *spec.args.toTypedArray())
        if (spec.cwd != null) pb.directory(java.io.File(spec.cwd))
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< ef9064288c7c987cfc672a3776a17427ea70b918
        // Sentinel Security Guard: Filter caller environment to prevent leaking or injecting secret-shaped keys (Disposition.BLOCKED)
        val safeEnv = spec.env.filterKeys {
            borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(it) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED
||||||| merged common ancestors
<<<<<<<<< Temporary merge branch 1
        // Sentinel Security Guard: Filter caller environment to prevent leaking or injecting secret-shaped keys (Disposition.BLOCKED)
        val safeEnv = spec.env.filterKeys {
            borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(it) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED
=======
        pb.environment().apply {
            clear()
            putAll(GuestEnvironment.curated())
            putAll(spec.env.filterKeys { GuestEnvironment.deliberate(it) != GuestEnvironment.Disposition.BLOCKED })
>>>>>>> origin/codex/hermes-source-pin
        }
<<<<<<< HEAD
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(safeEnv) }
||||||| a39a8f9cb
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(spec.env) }
=======
        val filteredEnv = spec.env.filter { (k, _) -> borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(k) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED }
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(filteredEnv) }
>>>>>>> fcd790e4bc4f302a77f384b40929a0b214ba6039
||||||| a39a8f9cb
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(spec.env) }
=======
        pb.environment().apply {
            clear()
            putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated())
            spec.env.forEach { (k, v) -> if (borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(k) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED) put(k, v) }
        }
>>>>>>> origin/jules-15618541591352001693-58b4bcf2
||||||| merged common ancestors
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(safeEnv) }
||||||||| a39a8f9cb
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(spec.env) }
=========
        val filteredEnv = spec.env.filter { (k, _) -> borg.trikeshed.graal.subvm.GuestEnvironment.deliberate(k) != borg.trikeshed.graal.subvm.GuestEnvironment.Disposition.BLOCKED }
        pb.environment().apply { clear(); putAll(borg.trikeshed.graal.subvm.GuestEnvironment.curated()); putAll(filteredEnv) }
>>>>>>>>> Temporary merge branch 2
=======
>>>>>>> origin/codex/hermes-source-pin
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
