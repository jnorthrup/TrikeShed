package borg.literbike.ccek.quic

// ============================================================================
// QUIC Server -- ported from quic_server.rs
// UDP-based QUIC server with connection multiplexing, TLS decryption support
// ============================================================================

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * Encode a variable-length integer per RFC 9000 Section 16
 */
fun encodeVarint(value: ULong): List<UByte> {
    val buf = mutableListOf<UByte>()
    when {
        value < 64uL -> {
            buf.add(value.toUByte())
        }
        value < 16384uL -> {
            buf.add((0x40u or (value shr 8).toUByte()))
            buf.add(value.toUByte())
        }
        value < 1_073_741_824uL -> {
            buf.add((0x80u or ((value shr 24) and 0xFFu).toUByte()))
            buf.add(((value shr 16) and 0xFFu).toUByte())
            buf.add(((value shr 8) and 0xFFu).toUByte())
            buf.add((value and 0xFFu).toUByte())
        }
        else -> {
            buf.add((0xC0u or ((value shr 56) and 0xFFu).toUByte()))
            buf.add(((value shr 48) and 0xFFu).toUByte())
            buf.add(((value shr 40) and 0xFFu).toUByte())
            buf.add(((value shr 32) and 0xFFu).toUByte())
            buf.add(((value shr 24) and 0xFFu).toUByte())
            buf.add(((value shr 16) and 0xFFu).toUByte())
            buf.add(((value shr 8) and 0xFFu).toUByte())
            buf.add((value and 0xFFu).toUByte())
        }
    }
    return buf
}

/**
 * Build a minimal QPACK-encoded HEADERS block with only :status 200.
 * Uses QPACK static table entries (no dynamic table, no Huffman).
 */
fun buildH3HeadersBlock(contentType: String): List<UByte> {
    val block = mutableListOf<UByte>()
    // QPACK Required Insert Count = 0, S=0, Delta Base = 0
    block.add(0x00u)
    block.add(0x00u)
    // :status 200 -- static table index 25 => 0xD9
    block.add(0xD9u)

    // Add explicit content-type via QPACK static table
    val ctIndex = when (contentType) {
        "text/html; charset=utf-8" -> 52u
        "text/css" -> 51u
        "image/png" -> 50u
        else -> 53u  // text/plain
    }
    block.add(0xC0u or ctIndex)
    return block
}

/** Wrap body in HTTP/3 HEADERS frame (type=0x01) + DATA frame (type=0x00) */
fun buildH3Response(contentType: String, body: List<UByte>): List<UByte> {
    val headersBlock = buildH3HeadersBlock(contentType)

    val out = mutableListOf<UByte>()
    // HEADERS frame: type=0x01, length=varint, payload=headersBlock
    out.addAll(encodeVarint(0x01u))
    out.addAll(encodeVarint(headersBlock.size.toULong()))
    out.addAll(headersBlock)

    // DATA frame: type=0x00, length=varint, payload=body
    out.addAll(encodeVarint(0x00u))
    out.addAll(encodeVarint(body.size.toULong()))
    out.addAll(body)

    return out
}

/** Static response selection for HTTP/3 server */
fun selectStaticResponse(requestPayload: List<UByte>, streamId: ULong): Triple<List<UByte>, String, String> {
    val requestStr = requestPayload.toByteArray().decodeToString()

    if (requestPayload.contains("/index.css") || requestStr.contains("index.css")) {
        return listOf<UByte>() to "text/css" to "/index.css"
    }
    if (requestPayload.contains("/bw_test_pattern.png") || requestStr.contains("bw_test_pattern.png")) {
        return listOf<UByte>() to "image/png" to "/bw_test_pattern.png"
    }
    if (requestPayload.contains("/index.html") || requestStr.contains("index.html")) {
        val body = "<html><body>index.html</body></html>".encodeToByteArray().map { it.toUByte() }
        return body to "text/html; charset=utf-8" to "/index.html"
    }
    if (requestPayload.contains("/favicon.ico") || requestStr.contains("favicon.ico")) {
        return emptyList() to "image/x-icon" to "/favicon.ico"
    }

    // Fallback mapping based on stream ID
    return when (streamId / 4uL) {
        0uL -> {
            val body = "<html><body>index.html</body></html>".encodeToByteArray().map { it.toUByte() }
            Triple(body, "text/html; charset=utf-8", "/index.html")
        }
        1uL -> Triple(emptyList(), "text/css", "/index.css")
        2uL -> Triple(emptyList(), "image/png", "/bw_test_pattern.png")
        else -> Triple("not found".encodeToByteArray().map { it.toUByte() }, "text/plain", "/not-found")
    }
}

private fun List<UByte>.contains(substring: String): Boolean {
    val bytes = this.toByteArray()
    val subBytes = substring.encodeToByteArray()
    return bytes.windowed(subBytes.size).any { it.contentEquals(subBytes) }
}

/**
 * QUIC Server -- ported from Rust QuicServer.
 *
 * UDP-based QUIC server with:
 * - Connection multiplexing by DCID
 * - Coalesced packet parsing
 * - Optional Initial packet decryption (TLS feature)
 * - Static HTTP/3 response serving
 */
class QuicServer(
    private val port: UShort,
    private val ctx: CoroutineContext? = null
) {
    private val connections = mutableMapOf<String, QuicEngine>()
    private val channel = DatagramChannel.open().apply {
        configureBlocking(false)
        socket().bind(InetSocketAddress(port.toInt()))
    }

    companion object {
        /** Extract DCID from long header (RFC 9000 Section 5.2) */
        fun extractDcidFromLongHeader(bytes: List<UByte>): List<UByte>? {
            if (bytes.isEmpty() || (bytes[0].toInt() and 0x80) == 0) return null
            if (bytes.size < 6) return null
            val dcidLen = bytes[5].toInt()
            if (bytes.size < 6 + dcidLen) return null
            return bytes.subList(6, 6 + dcidLen)
        }

        /** Minimal variable-length integer decoder per RFC 9000 Section 16 */
        fun readVarint(buf: List<UByte>, pos: Int): Pair<ULong, Int>? {
            if (pos >= buf.size) return null
            val prefix = (buf[pos].toInt() shr 6) and 0x03
            val byteLen = 1 shl prefix
            if (pos + byteLen > buf.size) return null
            var value: ULong = (buf[pos].toUInt() and 0x3Fu).toULong()
            for (i in 1 until byteLen) {
                value = (value shl 8) or buf[pos + i].toULong()
            }
            return value to byteLen
        }

        /** Returns the byte length of the first QUIC packet in a UDP datagram */
        fun firstPacketLen(packetData: List<UByte>): Int? {
            if (packetData.isEmpty()) return null

            // Short header: packet length is not encoded; assume it consumes the rest
            if ((packetData[0].toInt() and 0x80) == 0) return packetData.size

            // Long header
            var pos = 1
            if (pos + 4 > packetData.size) return null
            pos += 4  // version

            if (pos >= packetData.size) return null
            val dcidLen = packetData[pos].toInt()
            pos += 1
            if (pos + dcidLen > packetData.size) return null
            pos += dcidLen

            if (pos >= packetData.size) return null
            val scidLen = packetData[pos].toInt()
            pos += 1
            if (pos + scidLen > packetData.size) return null
            pos += scidLen

            // Initial includes token field
            val packetTypeBits = (packetData[0].toInt() shr 4) and 0x03
            if (packetTypeBits == 0) {
                val (tokenLen, tokenVarLen) = readVarint(packetData, pos) ?: return null
                pos += tokenVarLen
                if (pos + tokenLen.toInt() > packetData.size) return null
                pos += tokenLen.toInt()
            }

            val (payloadLen, payloadLenVarLen) = readVarint(packetData, pos) ?: return null
            pos += payloadLenVarLen

            val totalLen = pos + payloadLen.toInt()
            if (totalLen > packetData.size) return null
            return totalLen
        }

        /** Split coalesced QUIC packets from a UDP datagram */
        fun splitCoalescedPackets(datagram: List<UByte>): List<List<UByte>> {
            val packets = mutableListOf<List<UByte>>()
            var offset = 0

            while (offset < datagram.size) {
                val remaining = datagram.subList(offset, datagram.size)
                val pktLen = firstPacketLen(remaining)
                if (pktLen == null || pktLen == 0 || pktLen > remaining.size) {
                    packets.add(remaining)
                    break
                }
                packets.add(remaining.subList(0, pktLen))
                offset += pktLen

                // Short-header packets cannot be split further without decryption context
                if ((remaining[0].toInt() and 0x80) == 0) break
            }

            return packets
        }
    }

    /** Bind the server and start accepting connections */
    fun bind(): Result<Unit> = runCatching {
        println("QUIC server bound on port $port")
    }

    /** Process incoming datagram */
    suspend fun processDatagram(data: List<UByte>, remoteAddr: String): Result<Unit> = runCatching {
        // Split coalesced packets
        val packets = splitCoalescedPackets(data)

        for (packetData in packets) {
            // Extract DCID for connection routing
            val dcid = extractDcidFromLongHeader(packetData)
            val connKey = dcid?.joinToString(":") { it.toString(16) } ?: remoteAddr

            val engine = connections.getOrPut(connKey) {
                val initialState = QuicConnectionState(
                    remoteConnectionId = dcid?.let { ConnectionId(it) } ?: ConnectionId.random(8),
                    localConnectionId = ConnectionId.random(8)
                )
                QuicEngine(
                    role = QuicRole.Server,
                    initialState = initialState,
                    coroutineContext = ctx
                )
            }

            // Try to parse the packet
            val packet = parsePacket(packetData)
            if (packet != null) {
                engine.processPacket(packet)
            }
        }
    }

    /** Parse raw bytes into a QuicPacket (simplified) */
    private fun parsePacket(data: List<UByte>): QuicPacket? {
        if (data.isEmpty()) return null

        val firstByte = data[0]
        val packetType = QuicPacketType.fromFirstByte(firstByte)

        // Simplified parsing -- full impl would decode all header fields
        val header = QuicHeader(
            type = packetType,
            version = 1uL,
            destinationConnectionId = ConnectionId.EMPTY,
            sourceConnectionId = ConnectionId.EMPTY,
            packetNumber = 0uL,
            token = null
        )

        return QuicPacket(
            header = header,
            frames = emptyList(),
            payload = data.subList(1, data.size)
        )
    }

    /** Get connection by key */
    fun getConnection(key: String): QuicEngine? = connections[key]

    /** Get all connections */
    fun getConnections(): Map<String, QuicEngine> = connections.toMap()

    /** Close the server */
    fun close() {
        channel.close()
    }
}
