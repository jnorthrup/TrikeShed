package borg.trikeshed.torrent

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * uTP connection manager — manages a single UDP socket and routes incoming
 * packets to UtpSocket instances.
 *
 * Each (address, connId) pair maps to one UtpSocket.  The manager binds one
 * [DatagramChannel] on construction, runs a receive loop on [Dispatchers.IO],
 * and sends outgoing datagrams through the same channel — matching the
 * pattern established by [borg.trikeshed.litebike.JvmMulticastAdapter].
 */
open class UtpManager(
    private val scope: CoroutineScope,
    private val bindPort: Int = 6882,
) {

    private val connections = mutableMapOf<ConnectionKey, UtpSocket>()

    private data class ConnectionKey(val address: PeerAddress, val connId: Int)

    /** Inbound packet queue — the receive loop pushes, dispatch drains. */
    private val inbound = Channel<InboundPacket>(Channel.UNLIMITED)

    private data class InboundPacket(val sender: PeerAddress, val data: ByteArray)

    private var channel: DatagramChannel? = null
    private var receiveJob: Job? = null

    /** Bound port (actual, after bind). */
    val port: Int get() = (channel?.getLocalAddress() as? InetSocketAddress)?.port ?: bindPort

    /**
     * Bind the UDP socket and start the receive loop.
     * Called lazily on first [connect], or explicitly by the engine.
     */
    fun start() {
        if (channel != null) return
        val dc = DatagramChannel.open()
        dc.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        dc.bind(InetSocketAddress(bindPort))
        channel = dc
        receiveJob = scope.launch(Dispatchers.IO) {
            val buf = ByteBuffer.allocate(2048)
            while (isActive) {
                buf.clear()
                val sender = try {
                    dc.receive(buf)
                } catch (_: Exception) {
                    break
                } ?: continue
                buf.flip()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val addr = sender as? InetSocketAddress
                    ?: continue
                val peer = PeerAddress(addr.hostString, addr.port)
                inbound.trySend(InboundPacket(peer, bytes))
            }
        }
        // Drain inbound packets and dispatch to sockets.
        scope.launch {
            for (pkt in inbound) {
                routeInbound(pkt.sender, pkt.data)
            }
        }
    }

    /**
     * Route a raw inbound UDP packet to the matching UtpSocket.
     */
    private fun routeInbound(sender: PeerAddress, data: ByteArray) {
        val header = decodeUtpHeader(data) ?: return
        val payload = data.copyOfRange(20, data.size)
        val recvTime = System.nanoTime() / 1000
        // Try both connId and connId xor 1 — the responder's id is flipped.
        val direct = connections[ConnectionKey(sender, header.connId)]
            ?: connections[ConnectionKey(sender, header.connId xor 1)]
        direct?.handlePacket(header, payload, recvTime)
    }

    /**
     * Connect to a peer over uTP.
     * Returns a UtpSocket ready to send/receive.
     */
    fun connect(
        address: PeerAddress,
        connId: Int,
        onReceive: (UtpHeader, ByteArray) -> Unit,
    ): UtpSocket {
        if (channel == null) start()
        val key = ConnectionKey(address, connId)
        return connections.getOrPut(key) {
            UtpSocket(connId = connId, onSend = { wire -> sendUdp(address, wire) }, onReceive = { data ->
                dispatch(address, connId, data, onReceive)
            })
        }
    }

    private fun dispatch(
        address: PeerAddress,
        recvConnId: Int,
        data: ByteArray,
        onReceive: (UtpHeader, ByteArray) -> Unit,
    ) {
        val header = decodeUtpHeader(data) ?: return
        val payload = data.copyOfRange(20, data.size)
        // Route to the right socket — receiver flips connId
        val key = ConnectionKey(address, recvConnId xor 1)
        connections[key]?.handlePacket(header, payload, System.nanoTime() / 1000)
            ?: connections[ConnectionKey(address, recvConnId)]?.handlePacket(header, payload, System.nanoTime() / 1000)
    }

    private fun sendUdp(address: PeerAddress, wire: ByteArray) {
        val dc = channel ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val buf = ByteBuffer.wrap(wire)
                dc.send(buf, InetSocketAddress(address.host, address.port))
            } catch (_: Exception) { }
        }
    }

    fun close() {
        receiveJob?.cancel()
        connections.values.forEach { it.close() }
        connections.clear()
        inbound.close()
        runCatching { channel?.close() }
        channel = null
    }
}
