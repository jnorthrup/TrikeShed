@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package borg.trikeshed.userspace.nio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createListeningSocket(host: String, port: Int): ListeningSocket =
    PosixListeningSocket(host, port)

actual fun createConnectedSocket(host: String, port: Int): ConnectedSocket =
    PosixConnectedSocket(host, port)

class PosixConnectedSocket(val host: String, val port: Int) : ConnectedSocket {
    override val remoteAddress: SocketAddress
        get() = SocketAddress.Inet(host, port)

    override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int =
        withContext(Dispatchers.Default) { -1 }

    override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int =
        withContext(Dispatchers.Default) { len }

    override suspend fun close() {
        withContext(Dispatchers.Default) {}
    }
}

class PosixListeningSocket(override val bindAddress: SocketAddress.Inet) : ListeningSocket {
    constructor(host: String, port: Int) : this(SocketAddress.Inet(host, port))

    override suspend fun accept(): ConnectedSocket? =
        withContext(Dispatchers.Default) { null }

    override suspend fun close() {
        withContext(Dispatchers.Default) {}
    }
}
