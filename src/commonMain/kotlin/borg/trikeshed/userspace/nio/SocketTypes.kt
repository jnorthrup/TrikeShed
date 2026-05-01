package borg.trikeshed.userspace.nio

sealed class SocketAddress {
    data class Inet(val host: String, val port: Int) : SocketAddress()
    data class Unix(val path: String) : SocketAddress()
}

interface ConnectedSocket {
    val remoteAddress: SocketAddress
    suspend fun read(buf: ByteArray, offset: Int, len: Int): Int
    suspend fun write(buf: ByteArray, offset: Int, len: Int): Int
    suspend fun close()
}

interface ListeningSocket {
    val bindAddress: SocketAddress
    suspend fun accept(): ConnectedSocket?
    suspend fun close()
}
