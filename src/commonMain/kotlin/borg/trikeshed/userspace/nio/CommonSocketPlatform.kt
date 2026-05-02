package borg.trikeshed.userspace.nio

class CommonConnectedSocket(
    override val remoteAddress: SocketAddress,
) : ConnectedSocket {
    override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int = -1

    override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int = len

    override suspend fun close() {}
}

class CommonListeningSocket(
    override val bindAddress: SocketAddress,
) : ListeningSocket {
    override suspend fun accept(): ConnectedSocket? = null

    override suspend fun close() {}
}

fun commonCreateListeningSocket(host: String, port: Int): ListeningSocket =
    CommonListeningSocket(SocketAddress.Inet(host, port))

fun commonCreateConnectedSocket(host: String, port: Int): ConnectedSocket =
    CommonConnectedSocket(SocketAddress.Inet(host, port))
