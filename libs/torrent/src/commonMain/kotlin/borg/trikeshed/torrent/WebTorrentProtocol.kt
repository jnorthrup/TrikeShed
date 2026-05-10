package borg.trikeshed.torrent

/**
 * WebTorrent protocol support (BEP 19).
 *
 * WebTorrent runs the standard BitTorrent wire protocol over WebSocket,
 * enabling browser-based peers to join the swarm.
 *
 * WebSocket handshake:
 *   GET /announce?info_hash=<hex>&peer_id=<hex> HTTP/1.1
 *   Upgrade: websocket
 *   Sec-WebSocket-Protocol: bittorrent
 *
 * After upgrade, binary frames carry standard BitTorrent wire messages.
 */
object WebTorrentProtocol {

    /** WebSocket sub-protocol identifier for BitTorrent. */
    const val SUB_PROTOCOL = "bittorrent"

    /** Default WebTorrent tracker port. */
    const val DEFAULT_PORT = 8000

    /**
     * Build a WebSocket upgrade request for tracker announce.
     * @param trackerHost the WebTorrent tracker host
     * @param infoHash 20-byte info hash (hex-encoded in URL)
     * @param peerId 20-byte peer ID (hex-encoded in URL)
     * @param port our listening port
     */
    fun buildHandshakeRequest(
        trackerHost: String,
        infoHashHex: String,
        peerIdHex: String,
        port: Int = DEFAULT_PORT,
    ): String = buildString {
        append("GET /announce?info_hash=$infoHashHex&peer_id=$peerIdHex&port=$port HTTP/1.1\r\n")
        append("Host: $trackerHost\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("Sec-WebSocket-Protocol: $SUB_PROTOCOL\r\n")
        append("\r\n")
    }

    /**
     * Verify a WebSocket upgrade response. Returns true if the server accepted.
     */
    fun verifyHandshakeResponse(response: String): Boolean {
        val lines = response.split("\r\n")
        val status = lines.firstOrNull() ?: return false
        return status.contains("101") &&
            lines.any { it.startsWith("Sec-WebSocket-Protocol:", ignoreCase = true) && it.contains(SUB_PROTOCOL, ignoreCase = true) }
    }

    /**
     * Wire protocol message types — same as standard BitTorrent but delivered
     * as binary WebSocket frames instead of raw TCP.
     */
    enum class WireMessage(val id: Int) {
        CHOKE(0), UNCHOKE(1), INTERESTED(2), NOT_INTERESTED(3),
        HAVE(4), BITFIELD(5), REQUEST(6), PIECE(7), CANCEL(8),
        PORT(9),  // DHT port message
        EXTENSION(20),  // BEP 10 extension protocol
    }

    /**
     * Build a wire protocol message as a binary frame.
     * Format: <4-byte big-endian length prefix> <1-byte message id> <payload>
     */
    fun buildWireMessage(msgId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 1 + payload.size
        val result = ByteArray(4 + len)
        result[0] = ((len shr 24) and 0xFF).toByte()
        result[1] = ((len shr 16) and 0xFF).toByte()
        result[2] = ((len shr 8) and 0xFF).toByte()
        result[3] = (len and 0xFF).toByte()
        result[4] = msgId.toByte()
        payload.copyInto(result, 5)
        return result
    }

    /**
     * Parse a wire message from a binary frame. Returns (msgId, payload).
     */
    fun parseWireMessage(data: ByteArray): Pair<Int, ByteArray>? {
        if (data.size < 5) return null
        val len = ((data[0].toInt() and 0xFF) shl 24) or
            ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or
            (data[3].toInt() and 0xFF)
        if (data.size < 4 + len) return null
        val msgId = data[4].toInt() and 0xFF
        val payload = if (len > 1) data.copyOfRange(5, 4 + len) else ByteArray(0)
        return msgId to payload
    }

    /** Keep-alive message: 4 zero bytes (length = 0). */
    val KEEP_ALIVE: ByteArray = ByteArray(4)
}
