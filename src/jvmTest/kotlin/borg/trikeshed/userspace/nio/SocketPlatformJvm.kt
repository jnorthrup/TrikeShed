package borg.trikeshed.userspace.nio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException

actual fun createListeningSocket(host: String, port: Int): ListeningSocket =
    JvmListeningSocket(ServerSocket(port, 0, java.net.InetAddress.getByName(host)))

actual fun createConnectedSocket(host: String, port: Int): ConnectedSocket =
    JvmConnectedSocket(Socket(host, port))

class JvmConnectedSocket(private val socket: Socket) : ConnectedSocket {
    override val remoteAddress: SocketAddress
        get() = SocketAddress.Inet(socket.inetAddress.hostAddress, socket.port)

    override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int =
        withContext(Dispatchers.IO) {
            try {
                socket.getInputStream().read(buf, offset, len)
            } catch (e: IOException) {
                -1
            }
        }

    override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int =
        withContext(Dispatchers.IO) {
            socket.getOutputStream().write(buf, offset, len)
            len
        }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            try {
                socket.close()
            } catch (_: Throwable) {}
        }
    }
}

class JvmListeningSocket(private val server: ServerSocket) : ListeningSocket {
    override val bindAddress: SocketAddress
        get() = SocketAddress.Inet(server.inetAddress.hostAddress ?: "0.0.0.0", server.localPort)

    override suspend fun accept(): ConnectedSocket? =
        withContext(Dispatchers.IO) {
            try {
                val s = server.accept()
                JvmConnectedSocket(s)
            } catch (e: IOException) {
                null
            }
        }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            try {
                server.close()
            } catch (_: Throwable) {}
        }
    }
}
