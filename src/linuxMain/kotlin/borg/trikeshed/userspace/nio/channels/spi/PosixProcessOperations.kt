@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.cinterop.*
import platform.posix.*
import platform.linux.posix_spawn_file_actions_t
import platform.linux.posix_spawn_file_actions_tVar
import platform.linux.posix_spawn_file_actions_addopen
import platform.linux.posix_spawn_file_actions_init
import platform.linux.posix_spawn_file_actions_destroy
import platform.linux.posix_spawnp

class PosixProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        val stdinPath = stdin?.let { createTempFile(it) }
        val stdoutPath = createTempFile(byteArrayOf())
            ?: return ProcessResult(-1, byteArrayOf(), "mkstemp(stdout) failed".encodeToByteArray()).also {
                stdinPath?.let(::unlink)
            }
        val stderrPath = createTempFile(byteArrayOf())
            ?: return ProcessResult(-1, byteArrayOf(), "mkstemp(stderr) failed".encodeToByteArray()).also {
                stdinPath?.let(::unlink)
                unlink(stdoutPath)
            }

        val exitCode = memScoped {
            val pid = alloc<pid_tVar>()
            val actions = alloc<posix_spawn_file_actions_tVar>()
            posix_spawn_file_actions_init(actions.ptr)

            if (stdinPath != null) {
                posix_spawn_file_actions_addopen(
                    actions.ptr, STDIN_FILENO, stdinPath, O_RDONLY, 0u
                )
            }

            // 420u is 0644 octal (S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH)
            val mode = (S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH).toUInt()
            posix_spawn_file_actions_addopen(
                actions.ptr, STDOUT_FILENO, stdoutPath, O_WRONLY or O_CREAT or O_TRUNC, mode
            )

            posix_spawn_file_actions_addopen(
                actions.ptr, STDERR_FILENO, stderrPath, O_WRONLY or O_CREAT or O_TRUNC, mode
            )

            // We use 'env' to set environment variables and inherit the rest, similar to the original behavior
            val argv = allocArray<CPointerVar<ByteVar>>(env.size + args.size + 3)
            var argIndex = 0

            argv[argIndex++] = "env".cstr.ptr
            env.forEach { (key, value) ->
                argv[argIndex++] = "$key=$value".cstr.ptr
            }
            argv[argIndex++] = command.cstr.ptr
            args.forEach { arg ->
                argv[argIndex++] = arg.cstr.ptr
            }
            argv[argIndex] = null

            // Pass environ for envp to inherit the current environment (which 'env' will then augment)
            val spawnResult = posix_spawnp(pid.ptr, "env", actions.ptr, null, argv, platform.posix.environ)
            posix_spawn_file_actions_destroy(actions.ptr)

            if (spawnResult != 0) {
                return@memScoped -1
            }

            val status = alloc<IntVar>()
            var waitRes: Int
            do {
                waitRes = waitpid(pid.value, status.ptr, 0)
            } while (waitRes == -1 && errno == EINTR)

            if ((status.value and 0x7F) == 0) {
                // WIFEXITED
                (status.value shr 8) and 0xFF
            } else {
                -1
            }
        }

        val stdout = readFile(stdoutPath)
        val stderr = readFile(stderrPath)

        stdinPath?.let(::unlink)
        unlink(stdoutPath)
        unlink(stderrPath)

        return ProcessResult(exitCode, stdout, stderr)
    }

    private fun createTempFile(bytes: ByteArray): String? = memScoped {
        val template = "/tmp/trikeshed-process-XXXXXX".cstr.placeTo(this)
        val fd = mkstemp(template)
        if (fd < 0) {
            return null
        }
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                    if (written <= 0) {
                        close(fd)
                        unlink(template.toKString())
                        return null
                    }
                    offset += written.toInt()
                }
            }
        }
        close(fd)
        return template.toKString()
    }

    private fun readFile(path: String): ByteArray {
        val fp = fopen(path, "rb") ?: return byteArrayOf()
        return try {
            memScoped {
                val out = ByteAccumulator()
                val buf = allocArray<ByteVar>(4096)
                while (true) {
                    val read = fread(buf, 1.convert(), 4096.convert(), fp).toInt()
                    if (read <= 0) {
                        break
                    }
                    out.append(buf, read)
                }
                out.toByteArray()
            }
        } finally {
            fclose(fp)
        }
    }
}

private class ByteAccumulator(initialCapacity: Int = 4096) {
    private var storage = ByteArray(initialCapacity)
    private var size = 0

    fun append(bytes: CPointer<ByteVar>, count: Int) {
        ensure(size + count)
        for (index in 0 until count) {
            storage[size + index] = bytes[index]
        }
        size += count
    }

    fun toByteArray(): ByteArray = storage.copyOf(size)

    private fun ensure(required: Int) {
        if (required <= storage.size) {
            return
        }
        var next = storage.size
        while (next < required) {
            next *= 2
        }
        storage = storage.copyOf(next)
    }
}