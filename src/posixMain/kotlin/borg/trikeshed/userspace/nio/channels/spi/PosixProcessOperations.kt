@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi
import borg.trikeshed.userspace.nio.posix.trikeshed_spawn
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*

class PosixProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val temporary = mutableListOf<String>()
        fun capture(bytes: ByteArray): String =
            checkNotNull(createTempFile(bytes)) { "mkstemp or stdin write failed" }.also(temporary::add)
        try {
            val stdinPath = stdin?.let(::capture)
            val stdoutPath = capture(byteArrayOf())
            val stderrPath = capture(byteArrayOf())
            val exitCode = memScoped {
                val pid = alloc<pid_tVar>()
                // env applies overrides to the inherited environment without shell parsing.
                val argv = allocArray<CPointerVar<ByteVar>>(env.size + args.size + 3)
                var index = 0
                argv[index++] = "env".cstr.ptr
                env.forEach { (key, value) -> argv[index++] = "$key=$value".cstr.ptr }
                argv[index++] = command.cstr.ptr
                args.forEach { argv[index++] = it.cstr.ptr }
                argv[index] = null
                val spawned = trikeshed_spawn(pid.ptr, stdinPath, stdoutPath, stderrPath, argv)
                check(spawned == 0) { "posix_spawnp failed: $spawned" }
                val status = alloc<IntVar>()
                var waited: pid_t
                do { waited = waitpid(pid.value, status.ptr, 0) }
                while (waited == -1 && errno == EINTR)
                check(waited == pid.value) { "waitpid failed: $errno" }
                if ((status.value and 0x7F) == 0) (status.value shr 8) and 0xFF else -1
            }
            ProcessResult(exitCode, borg.trikeshed.common.Files.readAllBytes(stdoutPath),
                borg.trikeshed.common.Files.readAllBytes(stderrPath))
        } finally {
            temporary.forEach { unlink(it) }
        }
    }

    private fun createTempFile(bytes: ByteArray): String? = memScoped {
            val template = "/tmp/trikeshed-process-XXXXXX".cstr.placeTo(this)
            val fd = mkstemp(template)
            if (fd < 0) {
                return@memScoped null
            }
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                        if (written < 0 && errno == EINTR) continue
                        if (written <= 0) {
                            close(fd)
                            unlink(template.toKString())
                            return@memScoped null
                        }
                        offset += written.toInt()
                    }
                }
            }
            close(fd)
            template.toKString()
        }
}
