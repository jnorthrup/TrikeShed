 package borg.trikeshed.htx.client

import borg.trikeshed.tls.codec.aead.Aes128Gcm
import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.codec.ecdh.DefaultX25519
import borg.trikeshed.tls.codec.hash.DefaultSha256
import borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256
import borg.trikeshed.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import kotlin.random.Random

/**
 * JVM QUIC transport through the uring facade.
 *
 * Opens a UDP DatagramSocket via ChannelOperations (SOCK_DGRAM),
 * connects it to the target, builds a QUIC Initial packet with
 * TLS 1.3 ClientHello in a CRYPTO frame, sends it, and reads the
 * server's response.
 *
 * All socket I/O routes through:
 *   JvmChannelOperations → Channel → FunctionalUringFacade → JvmUserspaceChannelBackend
 */
actual fun createQuicHandler(): HtxRequestHandler = { request -> fetchQuic(request) }

private val platformChannels = JvmChannelOperations()
private val platformReactor = JvmReactorOperations(platformChannels)

private suspend fun fetchQuic(request: HtxClientRequest): HtxClientMessage {
    val url = request.path.toString()
    val host = url.removePrefix("quic://").substringBefore(':')
    val port = url.substringAfter(":").substringBefore('/').toIntOrNull() ?: 443

    // AF_INET=2, SOCK_DGRAM=2, IPPROTO_UDP=17
    val fd = platformChannels.socket(2, 2, 17)
    check(fd >= 0) { "UDP socket failed" }

    return try {
        // Connect the datagram socket — sets default destination
        val connResult = platformChannels.connect(fd, host, port)
        check(connResult >= 0) { "UDP connect to $host:$port failed: $connResult" }

        // Build QUIC Initial packet
        val dcid = Random.nextBytes(8)
        val initialPacket = buildQuicInitial(dcid, host)

        // Send via the ring: enqueue SEND, submit, wait
        val sendResult = platformChannels.send(fd, ByteBuffer.wrap(initialPacket), 0L)
        if (sendResult <= 0) {
            return HtxClientMessage(
                status = 503,
                body = "QUIC send failed to $host:$port (result=$sendResult)",
            )
        }

        // Read response
        val recvBuf = ByteArray(2048)
        val recvResult = platformChannels.recv(fd, ByteBuffer.wrap(recvBuf), 0L)

        if (recvResult > 0) {
            // Parse response — for now just report what we got
            val respBytes = recvBuf.copyOfRange(0, recvResult.coerceAtMost(recvBuf.size))
            val isLongHeader = (respBytes[0].toInt() and 0x80) != 0
            val headerType = if (isLongHeader) {
                when (respBytes[0].toInt() and 0x30 shr 4) {
                    0 -> "Initial"
                    1 -> "0-RTT"
                    2 -> "Handshake"
                    3 -> "Retry"
                    else -> "Unknown"
                }
            } else "Short(1-RTT)"

            HtxClientMessage(
                status = 200,
                body = "QUIC response from $host:$port: $recvResult bytes, type=$headerType, dcid=${
                    dcid.joinToString("") { "%02x".format(it) }
                }",
            )
        } else {
            HtxClientMessage(
                status = 504,
                body = "QUIC timeout from $host:$port (sent ${initialPacket.size}B, no response)",
            )
        }
    } finally {
        platformChannels.close(fd)
    }
}

/**
 * Build a QUIC v1 Initial packet (RFC 9000 §17.2.2) with TLS ClientHello in CRYPTO frame.
 *
 * Derives QUIC initial keys per RFC 9001 §5.2:
 *   initial_salt = 0x38762cf7f55934b34d179ae6a4c80cadccbb7f0a
 *   initial_secret = HKDF-Extract(initial_salt, client_dcid)
 *   client_initial_secret = HKDF-Expand-Label(initial_secret, "client in", "", Hash.length)
 *   key = HKDF-Expand-Label(client_initial_secret, "quic key", "", 16)
 *   iv  = HKDF-Expand-Label(client_initial_secret, "quic iv", "", 12)
 *   hp  = HKDF-Expand-Label(client_initial_secret, "quic hp", "", 16)
 */
private fun buildQuicInitial(dcid: ByteArray, serverName: String): ByteArray {
    val sha256 = DefaultSha256()
    val hkdf = DefaultHkdfSha256(sha256)
    val aes = DefaultAes128Gcm()

    // RFC 9001 §5.2: QUIC v1 initial salt
    val initialSalt = byteArrayOf(
        0x38, 0x76, 0x2C, 0xF7.toByte(), 0xF5.toByte(), 0x59, 0x34, 0xB3.toByte(),
        0x4D, 0x17, 0x9A.toByte(), 0xE6.toByte(), 0xA4.toByte(), 0xC8.toByte(), 0x0C, 0xAD.toByte(),
        0xCC.toByte(), 0xBB.toByte(), 0x7F, 0x0A
    )

    // Derive initial secret
    val initialSecret = hkdf.extract(initialSalt, dcid)

    // QUIC Initial key derivation: RFC 9001 §5.1 with HKDF-Expand-Label (RFC 8446 §7.1)
    // The "tls13 " prefix is included by expandLabel per RFC 8446 convention.
    val clientInitialSecret = hkdf.expandLabel(initialSecret, "client in", ByteArray(0), 32)
    val key  = hkdf.expandLabel(clientInitialSecret, "quic key", ByteArray(0), 16)
    val iv   = hkdf.expandLabel(clientInitialSecret, "quic iv",  ByteArray(0), 12)
    val hpKey = hkdf.expandLabel(clientInitialSecret, "quic hp", ByteArray(0), 16)

    // Build TLS ClientHello for QUIC (ALPN: h3)
    val x25519 = DefaultX25519()
    val codec = CommonTlsRecordCodec(aes)
    val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, serverName, listOf("h3"))
    val clientHelloRaw = hs.buildClientHello()

    // Inject QUIC transport parameters (extension 0x0039) into ClientHello.
    // The handshake message is [type:1][length:3][body]. Body ends with [ext_len:2][ext_data].
    // We splice the new extension into the extensions block and update lengths.
    val clientHello = injectQuicTransportParams(clientHelloRaw)

    // Build CRYPTO frame (type=0x06, offset=0, then the ClientHello)
    val cryptoFrame = ByteArrayBuilder().apply {
        put8(0x06)  // CRYPTO frame type
        putQuicVarint(0L)  // offset
        putQuicVarint(clientHello.size.toLong())  // length
        put(clientHello)
    }.toByteArray()

    // Build the AEAD payload: CRYPTO frame + optional PADDING.
    // Header overhead: 1(flags) + 4(ver) + 1(dcid_len) + 8(dcid) + 1(scid_len) + 8(scid)
    //                 + 1(token_len) + 2(length_varint) = 26, plus pn(2) + tag(16) = 44.
    // To reach 1200 bytes total: payload should be ~1200 - 44 = ~1156 bytes.
    // But we can also pad OUTSIDE the AEAD payload (aioquic does this).
    // aioquic sends: ~460 bytes payload + 16 tag + 2 pn = ~478 in-Length, + 700 zero-pad outside Length.
    val targetPayloadSize = 460  // match aioquic's working payload size
    val paddingNeeded = (targetPayloadSize - cryptoFrame.size).coerceAtLeast(0)
    val payload = cryptoFrame + ByteArray(paddingNeeded)

    // Packet number: 0 (2-byte encoding, matching aioquic reference)
    val pn = byteArrayOf(0x00, 0x00)
    val pnLen = pn.size // 2

    // Build unprotected header (used as AAD)
    // Long header: 0xC0 | (pnLen - 1) = 0xC1
    val firstByte = (0xC0 or (pnLen - 1)).toByte()
    val scid = Random.nextBytes(8) // 8-byte SCID (servers may require non-empty SCID)
    val unprotectedHeader = ByteArrayBuilder().apply {
        put8(firstByte.toInt())                              // flags
        put32(0x00000001)                                    // version (QUIC v1)
        put8(dcid.size); put(dcid)                           // DCID len + DCID
        put8(scid.size); put(scid)                           // SCID len + SCID
        putQuicVarint(0L)                                    // token length = 0
        putQuicVarint((payload.size + pnLen + 16).toLong())  // length: payload + pn + tag
        put(pn)                                              // packet number
    }.toByteArray()

    // Encrypt payload: AES-128-GCM(key, iv XOR pn, aad=header_without_pn, plaintext=payload)
    val nonce = ByteArray(12)
    for (i in pn.indices) nonce[12 - pn.size + i] = pn[i]
    for (i in iv.indices) nonce[i] = (nonce[i].toInt() xor iv[i].toInt()).toByte()

    // AAD = full header INCLUDING packet number (confirmed by literbike server decrypt)
    val aad = unprotectedHeader
    val ciphertext = aes.seal(key, nonce, aad, payload)

    // Apply header protection: sample 16 bytes starting at PN position in [header||ciphertext]
    // RFC 9001 §5.4.2: "sample is taken from the protected packet starting at the Packet Number field"
    val pnOffset = unprotectedHeader.size - pnLen
    val sampleInput = unprotectedHeader.copyOfRange(pnOffset, unprotectedHeader.size) + ciphertext
    val sample = sampleInput.copyOfRange(0, 16.coerceAtMost(sampleInput.size))
    val mask = aesEcbEncrypt(hpKey, sample)

    val protectedHeader = unprotectedHeader.copyOf()
    // First byte: XOR bits 0-3 (for long header, mask[0] bits 0-1 for PN length)
    protectedHeader[0] = (protectedHeader[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
    // Packet number bytes: XOR with mask[1..pnLen]
    for (i in 0 until pnLen) {
        protectedHeader[pnOffset + i] = (protectedHeader[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
    }

    // Pad to 1200-byte minimum (RFC 9000 §14.1) — zero-fill outside the Length field
    val core = protectedHeader + ciphertext
    val padNeeded = (1200 - core.size).coerceAtLeast(0)
    return core + ByteArray(padNeeded)
}

/**
 * AES-ECB single-block encrypt for QUIC header protection (RFC 9001 §5.4).
 * Uses the AES-128 key schedule from DefaultAes128Gcm internally.
 */
private fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray {
    val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
    return cipher.doFinal(block)
}

/**
 * QUIC HKDF-Expand-Label: builds HKDF info WITHOUT the "tls13 " prefix.
 *
 * RFC 9001 §5.1 uses HKDF-Expand-Label (RFC 8446 §7.1) for QUIC Initial keys,
 * but the QUIC-specific labels ("client in", "quic key", etc.) are passed raw.
 * The "tls13 " prefix convention applies only to TLS cipher suite labels.
 */
private fun quicExpandLabel(
    hkdf: DefaultHkdfSha256,
    secret: ByteArray,
    label: String,
    context: ByteArray,
    length: Int
): ByteArray {
    val labelBytes = label.encodeToByteArray()
    // struct { uint16 length; opaque label<0..255>; opaque context<0..255>; }
    val info = ByteArray(2 + 1 + labelBytes.size + 1 + context.size)
    var p = 0
    info[p++] = ((length ushr 8) and 0xFF).toByte()
    info[p++] = (length and 0xFF).toByte()
    info[p++] = labelBytes.size.toByte()
    labelBytes.copyInto(info, p); p += labelBytes.size
    info[p++] = context.size.toByte()
    context.copyInto(info, p)
    return hkdf.expand(secret, info, length)
}

/** QUIC varint encoding (RFC 9000 §16). */
private fun ByteArrayBuilder.putQuicVarint(value: Long) {
    when {
        value < 0x40 -> put8(value.toInt())
        value < 0x4000 -> { put8(0x40 or ((value ushr 8) and 0x3F).toInt()); put8(value.toInt()) }
        value < 0x40000000 -> {
            put8(0x80 or ((value ushr 24) and 0x3F).toInt())
            put8(((value ushr 16) and 0xFF).toInt())
            put8(((value ushr 8) and 0xFF).toInt())
            put8(value.toInt())
        }
        else -> {
            put8(0xC0 or ((value ushr 56) and 0x3F).toInt())
            put8(((value ushr 48) and 0xFF).toInt())
            put8(((value ushr 40) and 0xFF).toInt())
            put8(((value ushr 32) and 0xFF).toInt())
            put8(((value ushr 24) and 0xFF).toInt())
            put8(((value ushr 16) and 0xFF).toInt())
            put8(((value ushr 8) and 0xFF).toInt())
            put8(value.toInt())
        }
    }
}

private fun ByteArrayBuilder.put16(v: Int) { put(byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())) }
private fun ByteArrayBuilder.put32(v: Int) { put(byteArrayOf(((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())) }

/**
 * Build minimal QUIC transport parameters (RFC 9000 §18).
 * Extension 0x0039 payload: series of transport parameter IDs (varint) + values (varint-length-prefixed).
 */
private fun buildQuicTransportParams(): ByteArray = ByteArrayBuilder().apply {
    // initial_max_streams_bidi (0x02): 100
    putQuicVarint(0x02); putQuicVarint(1); put8(100)
    // initial_max_data (0x04): 1048576 (1MB)
    putQuicVarint(0x04); putQuicVarint(4); put32(1048576)
    // initial_max_streams_uni (0x05): 100
    putQuicVarint(0x05); putQuicVarint(1); put8(100)
    // max_idle_timeout (0x01): 30000ms
    putQuicVarint(0x01); putQuicVarint(2); put16(30000)
    // initial_max_stream_data_bidi_local (0x06): 256KB
    putQuicVarint(0x06); putQuicVarint(3); put(byteArrayOf(0x04, 0x00, 0x00))
    // initial_max_stream_data_bidi_remote (0x07): 256KB
    putQuicVarint(0x07); putQuicVarint(3); put(byteArrayOf(0x04, 0x00, 0x00))
    // initial_max_stream_data_uni (0x08): 256KB
    putQuicVarint(0x08); putQuicVarint(3); put(byteArrayOf(0x04, 0x00, 0x00))
    // max_udp_payload_size (0x09): 65527
    putQuicVarint(0x09); putQuicVarint(2); put16(65527)
    // active_connection_id_limit (0x0E): 4
    putQuicVarint(0x0E); putQuicVarint(1); put8(4)
}.toByteArray()

/**
 * Inject QUIC transport parameters (extension 0x0039) into a TLS ClientHello.
 *
 * ClientHello structure: [type:1][length:3][body]
 * Body ends with: [extensions_length:2][extensions_data]
 * Each extension: [type:2][length:2][value]
 *
 * We append our extension, update extensions_length, and update the handshake length.
 */
private fun injectQuicTransportParams(hello: ByteArray): ByteArray {
    // hello[0] = type (0x01)
    // hello[1..3] = handshake length (body size)
    val hsLen = ((hello[1].toInt() and 0xFF) shl 16) or ((hello[2].toInt() and 0xFF) shl 8) or (hello[3].toInt() and 0xFF)
    // Body = hello[4..4+hsLen)
    val body = hello.copyOfRange(4, 4 + hsLen)

    // Find extensions: body layout is:
    // [version:2][random:32][session_id_len:1][session_id][cipher_suites_len:2][cipher_suites][comp_len:1][comp]
    // then [extensions_len:2][extensions]
    var p = 2 + 32 // skip version + random
    val sidLen = body[p].toInt() and 0xFF; p += 1 + sidLen
    val csLen = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF); p += 2 + csLen
    val compLen = body[p].toInt() and 0xFF; p += 1 + compLen

    // Now at extensions: [ext_len:2][ext_data]
    val extLen = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
    val extData = body.copyOfRange(p + 2, p + 2 + extLen)

    // Build the new extension 0x0039
    val tp = buildQuicTransportParams()
    val newExt = ByteArrayBuilder().apply {
        put16(0x0039) // extension type: quic_transport_parameters
        put16(tp.size) // extension data length
        put(tp)
    }.toByteArray()

    val newExtData = extData + newExt
    val newExtLen = newExtData.size

    // Rebuild body: everything before extensions + new extensions_len + new extensions
    val newBody = body.copyOfRange(0, p) +
            byteArrayOf(((newExtLen ushr 8) and 0xFF).toByte(), (newExtLen and 0xFF).toByte()) +
            newExtData

    // Rebuild handshake message
    val result = ByteArray(4 + newBody.size)
    result[0] = hello[0] // type
    result[1] = ((newBody.size ushr 16) and 0xFF).toByte()
    result[2] = ((newBody.size ushr 8) and 0xFF).toByte()
    result[3] = (newBody.size and 0xFF).toByte()
    newBody.copyInto(result, 4)
    return result
}

/** Simple byte array builder. */
private class ByteArrayBuilder {
    private val buf = mutableListOf<Byte>()
    fun put8(v: Int) { buf.add((v and 0xFF).toByte()) }
    fun put(data: ByteArray) { data.forEach { buf.add(it) } }
    fun toByteArray(): ByteArray = buf.toByteArray()
}
