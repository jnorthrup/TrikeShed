package borg.trikeshed.torrent

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * uTP connection manager — manages UDP sockets and routes incoming packets to UtpSocket instances.
 *
 * Each (address, connId) pair maps to one UtpSocket.
 */
open class UtpManager(private val scope: CoroutineScope) {

    private val connections = mutableMapOf<ConnectionKey, UtpSocket>()
    private val socket: DatagramSocket? = null // Created lazily on first send
    private val port = 6882

    private data class ConnectionKey(val address: PeerAddress, val connId: Int)

    /**
     * Connect to a peer over uTP.
     * Returns an UtpSocket ready to send/receive.
     */
    fun connect(
        address: PeerAddress,
        connId: Int,
        onReceive: (UtpHeader, ByteArray) -> Unit,
    ): UtpSocket {
        val key = ConnectionKey(address, connId)
        val socket = connections.getOrPut(key) {
            UtpSocket(connId = connId, onSend = { wire -> sendUdp(address, wire) }, onReceive = { data ->
                dispatch(address, connId, data, onReceive)
            })
        }
        return socket
    }

    private fun dispatch(
        address: PeerAddress,
        recvConnId: Int,
        data: ByteArray,
        onReceive: (UtpHeader, ByteArray) -> Unit,
    ) {
        val header = decodeUtpHeader(data) ?: return
        val payload = data.copyOfRange(20, data.size)
        // Route to the right socket
        val key = ConnectionKey(address, recvConnId xor 1) // receiver flips connId
        connections[key]?.handlePacket(header, payload, System.nanoTime() / 1000)
            ?: connections[ConnectionKey(address, recvConnId)]?.handlePacket(header, payload, System.nanoTime() / 1000)
    }

    private fun sendUdp(address: PeerAddress, wire: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket()
                val packet = DatagramPacket(wire, wire.size, InetAddress.getByName(address.host), address.port)
                sock.send(packet)
                sock.close()
            } catch (_: Exception) { }
        }
    }

    fun close() {
        connections.values.forEach { it.close() }
        connections.clear()
        socket?.close()
    }
}
