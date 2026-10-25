@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.mux

import borg.trikeshed.mcp.ChunkedLineSource
import borg.trikeshed.mcp.FramedLineSink
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.*

/**
 * Native entry point of the stdio MCP server, the same [McpStdio] the JVM runs, over file descriptors:
 * `entryPoint = "borg.trikeshed.mux.mcpStdioMain"`. This file is the whole platform layer: read(2) and write(2) on the process's
 * descriptors, and exit. Files, sockets, processes and TLS come from the supervisor's providers like everywhere else in commonMain.
 */
fun mcpStdioMain(args: Array<String>) {
    // A client that goes away must be an error the server can report, not a signal that ends the process in the middle of a reply.
    signal(SIGPIPE, SIG_IGN)
    val status = runBlocking { McpStdio.run(args, PosixStdioHost) }
    exit(status)
}

private object PosixStdioHost : McpStdioHost {
    override fun print(text: String) = writeFd(STDOUT_FILENO, "$text\n".encodeToByteArray())

    override fun diagnose(line: String) = writeFd(STDERR_FILENO, "$line\n".encodeToByteArray())

    override fun claimProtocol(): ProtocolChannel {
        // The protocol keeps the real stdout under a private, close-on-exec descriptor. Descriptor 1 itself becomes stderr, so a stray
        // print anywhere in the process lands in the log instead of in the middle of a reply, and no child process inherits the channel.
        val protocol = dup(STDOUT_FILENO)
        check(protocol >= 0) { "dup(stdout) failed (errno=$errno)" }
        check(fcntl(protocol, F_SETFD, FD_CLOEXEC) == 0) { "FD_CLOEXEC failed (errno=$errno)" }
        check(dup2(STDERR_FILENO, STDOUT_FILENO) >= 0) { "dup2(stderr, stdout) failed (errno=$errno)" }
        return ProtocolChannel(
            ChunkedLineSource({ buffer -> withContext(Dispatchers.IO) { readFd(STDIN_FILENO, buffer) } }),
            FramedLineSink { frame -> withContext(Dispatchers.IO) { writeFd(protocol, frame) } },
        )
    }
}

/** One read(2); end of input is the ChunkedLineSource's negative count, because zero there means "nothing yet". */
private fun readFd(fd: Int, buffer: ByteArray): Int {
    while (true) {
        val n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
        if (n > 0) return n.toInt()
        if (n == 0L) return -1
        check(errno == EINTR) { "read failed on descriptor $fd (errno=$errno)" }
    }
}

/** The whole of [bytes], or an exception: short writes continue, a signal retries, anything else is the caller's failure. */
private fun writeFd(fd: Int, bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val n = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).convert()) }
        if (n < 0) {
            check(errno == EINTR) { "write failed on descriptor $fd (errno=$errno)" }
            continue
        }
        offset += n.toInt()
    }
}
