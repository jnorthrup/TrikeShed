package borg.trikeshed.userspace.nio

actual fun createListeningSocket(host: String, port: Int): ListeningSocket =
    JsListeningSocket(SocketAddress.Inet(host, port))

actual fun createConnectedSocket(host: String, port: Int): ConnectedSocket =
    JsConnectedSocket(SocketAddress.Inet(host, port))

private class JsConnectedSocket(
    override val remoteAddress: SocketAddress,
) : ConnectedSocket {
    override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int = -1

    override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int = len

    override suspend fun close() {}
}

private class JsListeningSocket(
    override val bindAddress: SocketAddress,
) : ListeningSocket {
    override suspend fun accept(): ConnectedSocket? = null

    override suspend fun close() {}
}
