package borg.trikeshed.userspace.kernel

/**
 * POSIX socket operations ported from literbike.
 */

interface PosixSocket {
    fun fd(): Int
    fun bind(host: String, port: Int): Result<Unit>
    fun listen(backlog: Int): Result<Unit>
    fun accept(): Result<Pair<Int, String>>
    fun connect(host: String, port: Int): Result<Unit>
    fun send(buf: ByteArray, flags: Int): Result<Int>
    fun recv(buf: ByteArray, flags: Int): Result<Int>
    fun setNonblocking(nonblocking: Boolean): Result<Unit>
    fun setReuseAddr(reuse: Boolean): Result<Unit>
    fun setReusePort(reuse: Boolean): Result<Unit>
    fun close(): Result<Unit>
}

object SocketPair {
    fun newDgram(): Pair<Int, Int> {
        // Implementation varies by platform
        return Pair(-1, -1)
    }
}
