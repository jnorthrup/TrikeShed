package borg.trikeshed.platform

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.seriesOf

actual data class ProcessResult(
    actual val exitCode: Int,
    actual val stdout: String,
    actual val stderr: String
)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun getProgramName(): String {
    return platform.posix.getprogname()?.let { kotlinx.cinterop.toKString(it) } ?: "UnknownProgramNative"
}

object NativeMainArguments {
    var args: List<String> = emptyList()
}

actual fun getProgramArguments(): Series<String> {
    val explicitlyCaptured = NativeMainArguments.args
    if (explicitlyCaptured.isNotEmpty()) {
        return seriesOf(*explicitlyCaptured.toTypedArray())
    }

    @OptIn(kotlin.ExperimentalStdlibApi::class)
    return seriesOf(*kotlin.native.internal.Platform.getProgramArguments())
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun executeProcess(
    command: String,
    args: List<String>,
    input: String?,
    workingDir: String?
): ProcessResult {
    var tmpStdinPath: String? = null
    var tmpStdoutPath: String? = null
    var tmpStderrPath: String? = null

    try {
        kotlinx.cinterop.memScoped {
            val pid = kotlinx.cinterop.alloc<platform.posix.pid_tVar>()
            val actions = kotlinx.cinterop.alloc<platform.posix.posix_spawn_file_actions_tVar>()
            platform.posix.posix_spawn_file_actions_init(actions.ptr)

            var actualCommand = command
            var actualArgs = args

            if (workingDir != null) {
                actualCommand = "sh"
                val escapedCmd = command.replace("'", "'\\''")
                val escapedArgs = args.joinToString(" ") { it.replace("'", "'\\''").let { "'$it'" } }
                val script = "cd '$workingDir' && exec '$escapedCmd' $escapedArgs"
                actualArgs = listOf("-c", script)
            }

            if (input != null) {
                val templateStr = "/tmp/trikeshed-in-XXXXXX"
                val templateBytes = templateStr.encodeToByteArray()
                val templateBuf = kotlinx.cinterop.allocArray<kotlinx.cinterop.ByteVar>(templateBytes.size + 1)
                for (i in templateBytes.indices) templateBuf[i] = templateBytes[i]
                templateBuf[templateBytes.size] = 0.toByte()

                val fd = platform.posix.mkstemp(templateBuf)
                if (fd >= 0) {
                    tmpStdinPath = templateBuf.toKString()
                    val bytes = input.encodeToByteArray()
                    if (bytes.isNotEmpty()) {
                        bytes.usePinned { pinned ->
                            platform.posix.write(fd, pinned.addressOf(0), bytes.size.toULong())
                        }
                    }
                    platform.posix.close(fd)
                    platform.posix.posix_spawn_file_actions_addopen(actions.ptr, platform.posix.STDIN_FILENO, tmpStdinPath!!, platform.posix.O_RDONLY, 0u)
                }
            }

            val stdoutStr = "/tmp/trikeshed-out-XXXXXX"
            val stdoutBytes = stdoutStr.encodeToByteArray()
            val stdoutBuf = kotlinx.cinterop.allocArray<kotlinx.cinterop.ByteVar>(stdoutBytes.size + 1)
            for (i in stdoutBytes.indices) stdoutBuf[i] = stdoutBytes[i]
            stdoutBuf[stdoutBytes.size] = 0.toByte()

            val stdoutFd = platform.posix.mkstemp(stdoutBuf)
            if (stdoutFd >= 0) {
                tmpStdoutPath = stdoutBuf.toKString()
                platform.posix.close(stdoutFd)
                platform.posix.posix_spawn_file_actions_addopen(actions.ptr, platform.posix.STDOUT_FILENO, tmpStdoutPath!!, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC, 420u)
            }

            val stderrStr = "/tmp/trikeshed-err-XXXXXX"
            val stderrBytes = stderrStr.encodeToByteArray()
            val stderrBuf = kotlinx.cinterop.allocArray<kotlinx.cinterop.ByteVar>(stderrBytes.size + 1)
            for (i in stderrBytes.indices) stderrBuf[i] = stderrBytes[i]
            stderrBuf[stderrBytes.size] = 0.toByte()

            val stderrFd = platform.posix.mkstemp(stderrBuf)
            if (stderrFd >= 0) {
                tmpStderrPath = stderrBuf.toKString()
                platform.posix.close(stderrFd)
                platform.posix.posix_spawn_file_actions_addopen(actions.ptr, platform.posix.STDERR_FILENO, tmpStderrPath!!, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC, 420u)
            }

            val argv = kotlinx.cinterop.allocArray<kotlinx.cinterop.CPointerVar<kotlinx.cinterop.ByteVar>>(actualArgs.size + 2)
            var argIndex = 0

            val cmdBytes = actualCommand.encodeToByteArray()
            val cmdBuf = kotlinx.cinterop.allocArray<kotlinx.cinterop.ByteVar>(cmdBytes.size + 1)
            for (i in cmdBytes.indices) cmdBuf[i] = cmdBytes[i]
            cmdBuf[cmdBytes.size] = 0.toByte()
            argv[argIndex++] = cmdBuf

            actualArgs.forEach { arg ->
                val argBytes = arg.encodeToByteArray()
                val argBuf = kotlinx.cinterop.allocArray<kotlinx.cinterop.ByteVar>(argBytes.size + 1)
                for (i in argBytes.indices) argBuf[i] = argBytes[i]
                argBuf[argBytes.size] = 0.toByte()
                argv[argIndex++] = argBuf
            }
            argv[argIndex] = null

            val spawnResult = platform.posix.posix_spawnp(pid.ptr, actualCommand, actions.ptr, null, argv, null)
            platform.posix.posix_spawn_file_actions_destroy(actions.ptr)

            if (spawnResult != 0) {
                return ProcessResult(-1, "", "Failed to spawn process: $spawnResult")
            }

            val status = kotlinx.cinterop.alloc<kotlinx.cinterop.IntVar>()
            var waitRes: Int
            do {
                waitRes = platform.posix.waitpid(pid.value, status.ptr, 0)
            } while (waitRes == -1 && platform.posix.errno == platform.posix.EINTR)

            val exitCode = if ((status.value and 0x7F) == 0) {
                (status.value shr 8) and 0xFF
            } else {
                -1
            }

            val stdoutOutput = tmpStdoutPath?.let { readTextFile(it) } ?: ""
            val stderrOutput = tmpStderrPath?.let { readTextFile(it) } ?: ""

            return ProcessResult(exitCode, stdoutOutput, stderrOutput)
        }
    } finally {
        tmpStdinPath?.let { platform.posix.unlink(it) }
        tmpStdoutPath?.let { platform.posix.unlink(it) }
        tmpStderrPath?.let { platform.posix.unlink(it) }
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun readTextFile(path: String): String {
    val fp = platform.posix.fopen(path, "rb") ?: return ""
    return try {
        kotlinx.cinterop.memScoped {
            val buf = kotlinx.cinterop.allocArray<kotlinx.cinterop.ByteVar>(4096)
            val builder = StringBuilder()
            while (true) {
                val read = platform.posix.fread(buf, 1u, 4096u, fp).toInt()
                if (read <= 0) break
                val bytes = ByteArray(read)
                for (i in 0 until read) bytes[i] = buf[i]
                builder.append(bytes.decodeToString())
            }
            builder.toString()
        }
    } finally {
        platform.posix.fclose(fp)
    }
}
