@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.cinterop.*
import platform.posix.*

class PosixProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        val stdinPath = stdin?.let { createTempFile(it) }
        val stderrPath = createTempFile(byteArrayOf())
            ?: return ProcessResult(-1, byteArrayOf(), "mkstemp(stderr) failed".encodeToByteArray())

        val shellCommand = buildCommand(command, args, env, stdinPath, stderrPath)
        val fp = popen(shellCommand, "r")
            ?: return ProcessResult(-1, byteArrayOf(), "popen() failed".encodeToByteArray()).also {
                stdinPath?.let(::unlink)
                unlink(stderrPath)
            }

        val stdout = readPipe(fp)
        val raw = pclose(fp)
        val stderr = readFile(stderrPath)

        stdinPath?.let(::unlink)
        unlink(stderrPath)

        val exitCode = if (raw < 0) raw else raw ushr 8
        return ProcessResult(exitCode, stdout, stderr)
    }

    private fun buildCommand(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        stdinPath: String?,
        stderrPath: String,
    ): String = buildString {
        if (env.isNotEmpty()) {
            env.forEach { (key, value) ->
                append(key)
                append('=')
                append(shellQuote(value))
                append(' ')
            }
        }
        append(shellQuote(command))
        args.forEach { arg ->
            append(' ')
            append(shellQuote(arg))
        }
        if (stdinPath != null) {
            append(" < ")
            append(shellQuote(stdinPath))
        }
        append(" 2> ")
        append(shellQuote(stderrPath))
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

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

    private fun readPipe(fp: CPointer<FILE>): ByteArray = memScoped {
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
