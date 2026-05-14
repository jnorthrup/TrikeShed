package borg.trikeshed.userspace.kernel

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.spi.ByteRegion

/**
 * POSIX socket operations ported from literbike.
 */

interface PosixSocket {
    fun fd(): Int
    fun bind(host: CharSequence, port: Int): Result<Unit>
    fun listen(backlog: Int): Result<Unit>
    fun accept(): Result<Pair<Int, CharSequence>>
    fun connect(host: CharSequence, port: Int): Result<Unit>
    fun send(src: ByteSeries, flags: Int): Result<Int>
    fun recv(dst: ByteRegion, flags: Int): Result<Int>
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
