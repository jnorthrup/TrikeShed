package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.process.windowsSpawn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** exec through CreateProcessA (WindowsSpawn.kt), with no timeout, as the POSIX form has none. */
class MingwProcessOperations : ProcessOperations {
    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val result = windowsSpawn(command, args, env, stdin, null, 0L)
        ProcessResult(result.exitCode, result.stdout, result.stderr)
    }
}
