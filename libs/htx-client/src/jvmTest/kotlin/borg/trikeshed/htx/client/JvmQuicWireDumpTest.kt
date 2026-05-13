package borg.trikeshed.htx.client

import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.codec.ecdh.DefaultX25519
import borg.trikeshed.tls.codec.hash.DefaultSha256
import borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256
import borg.trikeshed.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive byte-level dump of QUIC Initial packet construction.
 * Each stage outputs hex so we can compare against RFC 9001 test vectors
 * or a known-good implementation packet capture.
 */
class JvmQuicWireDumpTest {

    private fun hex(buf: ByteArray): String = buf.joinToString(" ") { "%02x".format(it) }
    private fun hexFlat(buf: ByteArray): String = buf.joinToString("") { "%02x".format(it) }

    @Test
    fun `RFC 9001 Appendix A full packet structure dump`() {
        val sha256 = DefaultSha256()
        val hkdf = DefaultHkdfSha256(sha256)
        val aes = DefaultAes128Gcm()

        // Use a fixed DCID for reproducibility (same as RFC 9001 Appendix A)
        val dcid = byteArrayOf(0x83.toByte(), 0x94.toByte(), 0xC8.toByte(), 0xF0.toByte(), 0x3E, 0x51, 0x57, 0x08)
        val initialSalt = byteArrayOf(
            0x38, 0x76, 0x2C.toByte(), 0xF7.toByte(), 0xF5.toByte(), 0x59, 0x34, 0xB3.toByte(),
            0x4D, 0x17, 0x9A.toByte(), 0xE6.toByte(), 0xA4.toByte(), 0xC8.toByte(), 0x0C, 0xAD.toByte(),
            0xCC.toByte(), 0xBB.toByte(), 0x7F, 0x0A
        )

        println("=== DCID: ${hexFlat(dcid)} ===")
        println()

        // Key derivation
        val initialSecret = hkdf.extract(initialSalt, dcid)
        println("initial_secret:  ${hexFlat(initialSecret)}")

        val clientSecret = hkdf.expandLabel(initialSecret, "client in", ByteArray(0), 32)
        println("client_initial:  ${hexFlat(clientSecret)}")

        val key = hkdf.expandLabel(clientSecret, "quic key", ByteArray(0), 16)
        val iv = hkdf.expandLabel(clientSecret, "quic iv", ByteArray(0), 12)
        val hpKey = hkdf.expandLabel(clientSecret, "quic hp", ByteArray(0), 16)
        println("key:             ${hexFlat(key)}")
        println("iv:              ${hexFlat(iv)}")
        println("hp:              ${hexFlat(hpKey)}")
        println()

        // Verify against RFC 9001 Appendix A expected values
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", hexFlat(key), "key mismatch")
        assertEquals("fa044b2f42a3fd3b46fb255c", hexFlat(iv), "iv mismatch")
        assertEquals("9f50449e04a0e810283a1e9933adedd2", hexFlat(hpKey), "hp mismatch")
        println("✓ RFC 9001 Appendix A key derivation verified")
        println()

        // Build a minimal CRYPTO frame payload (just a PING + PADDING for structure test)
        // Actually let's use the real ClientHello from the TLS stack
        val x25519 = DefaultX25519()
        val codec = CommonTlsRecordCodec(aes)
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, "localhost", listOf("h3"))
        val clientHelloRaw = hs.buildClientHello()
        println("=== ClientHello (${clientHelloRaw.size} bytes) ===")
        println("handshake: type=${"%02x".format(clientHelloRaw[0])} len=${((clientHelloRaw[1].toInt() and 0xFF) shl 16 or ((clientHelloRaw[2].toInt() and 0xFF) shl 8) or (clientHelloRaw[3].toInt() and 0xFF))}")
        println("full hex: ${hexFlat(clientHelloRaw)}")
        println()

        // Inject transport parameters
        val clientHello = injectQuicTransportParams(clientHelloRaw)
        println("=== ClientHello with TP (${clientHello.size} bytes) ===")
        println("full hex: ${hexFlat(clientHello)}")

        // Parse extensions to verify TP injection
        var p = 6 + 32 // version + random
        val sidLen = clientHello[p].toInt() and 0xFF; p += 1 + sidLen
        val csLen = ((clientHello[p].toInt() and 0xFF) shl 8) or (clientHello[p + 1].toInt() and 0xFF); p += 2 + csLen
        val compLen = clientHello[p].toInt() and 0xFF; p += 1 + compLen
        val extLen = ((clientHello[p].toInt() and 0xFF) shl 8) or (clientHello[p + 1].toInt() and 0xFF)
        p += 2
        println("extensions (${extLen} bytes):")
        val extEnd = p + extLen
        var hasTP = false
        while (p < extEnd) {
            val extType = ((clientHello[p].toInt() and 0xFF) shl 8) or (clientHello[p + 1].toInt() and 0xFF)
            val extDataLen = ((clientHello[p + 2].toInt() and 0xFF) shl 8) or (clientHello[p + 3].toInt() and 0xFF)
            val name = when (extType) {
                0 -> "server_name"
                10 -> "supported_groups"
                13 -> "signature_algorithms"
                16 -> "alpn"
                43 -> "supported_versions"
                51 -> "key_share"
                57 -> "quic_transport_parameters"
                else -> "unknown($extType)"
            }
            println("  [$extType] $name: $extDataLen bytes")
            if (extType == 57) {
                hasTP = true
                println("    value: ${hex(clientHello.copyOfRange(p + 4, p + 4 + extDataLen))}")
            }
            p += 4 + extDataLen
        }
        assertTrue(hasTP, "Transport parameters extension (0x0039) must be present")
        println()

        // Build CRYPTO frame
        val cryptoFrame = byteArrayOf(0x06) + // CRYPTO frame type
                encodeQuicVarint(0L) + // offset
                encodeQuicVarint(clientHello.size.toLong()) + // length
                clientHello
        println("=== CRYPTO frame (${cryptoFrame.size} bytes) ===")
        println("type: 0x06 (CRYPTO)")
        println("offset: 0")
        println("payload_len: ${clientHello.size}")
        println()

        // Pad to 1200 minimum (RFC 9000 §14.1)
        val paddingNeeded = (1200 - 2 /* overhead */ - 2 /* pn */ - 16 /* tag */ - cryptoFrame.size).coerceAtLeast(0)
        val payload = cryptoFrame + ByteArray(paddingNeeded)
        println("=== Payload (CRYPTO + PADDING) (${payload.size} bytes) ===")
        println("crypto: ${cryptoFrame.size}B, padding: ${paddingNeeded}B")
        println()

        // Packet number
        val pn = byteArrayOf(0x00, 0x00) // 2-byte PN, value=0
        val pnLen = 2

        // Build unprotected header
        val firstByte = (0xC0 or (pnLen - 1)).toByte() // 0xC1
        println("=== Unprotected Header ===")
        println("first_byte: ${"%02x".format(firstByte)} (long header, initial, pn_len=$pnLen)")

        val headerBytes = mutableListOf<Byte>()
        headerBytes.add(firstByte)
        // version: 0x00000001 (QUIC v1)
        headerBytes.addAll(listOf(0x00, 0x00, 0x00, 0x01).map { it.toByte() })
        // DCID len + DCID
        headerBytes.add(dcid.size.toByte())
        headerBytes.addAll(dcid.toList())
        // SCID len = 0
        headerBytes.add(0)
        // Token len = 0
        headerBytes.add(0)
        // Length = payload.size + pnLen + 16 (tag)
        val lengthField = payload.size + pnLen + 16
        headerBytes.addAll(encodeQuicVarint(lengthField.toLong()).toList())
        // Packet number
        headerBytes.addAll(pn.toList())

        val unprotectedHeader = headerBytes.toByteArray()
        println("header (${unprotectedHeader.size} bytes): ${hex(unprotectedHeader)}")
        println("  first_byte:  ${"%02x".format(unprotectedHeader[0])}")
        println("  version:     ${hex(unprotectedHeader.copyOfRange(1, 5))}")
        println("  dcid_len:    ${unprotectedHeader[5]}")
        println("  dcid:        ${hex(unprotectedHeader.copyOfRange(6, 6 + unprotectedHeader[5]))}")
        val afterDcid = 6 + unprotectedHeader[5]
        println("  scid_len:    ${unprotectedHeader[afterDcid]}")
        val afterScid = afterDcid + 1
        println("  token_len:   ${unprotectedHeader[afterScid]}")
        val afterToken = afterScid + 1
        // varint length field
        val (lenVal, lenBytes) = decodeQuicVarint(unprotectedHeader, afterToken)
        println("  length:      $lenVal (${lenBytes} varint bytes)")
        val pnOffset = afterToken + lenBytes
        println("  pn_offset:   $pnOffset")
        println("  pn:          ${hex(unprotectedHeader.copyOfRange(pnOffset, pnOffset + pnLen))}")
        println()

        // Nonce construction
        val nonce = ByteArray(12)
        for (i in pn.indices) nonce[12 - pn.size + i] = pn[i]
        for (i in iv.indices) nonce[i] = (nonce[i].toInt() xor iv[i].toInt()).toByte()
        println("=== AEAD ===")
        println("nonce: ${hex(nonce)}")
        println("aad:   (${unprotectedHeader.size} bytes) ${hex(unprotectedHeader)}")
        println()

        // Encrypt
        val ciphertext = aes.seal(key, nonce, unprotectedHeader, payload)
        println("ciphertext+tag (${ciphertext.size} bytes):")
        // Print first 32 bytes and last 16 bytes (tag)
        if (ciphertext.size > 48) {
            println("  first 32: ${hex(ciphertext.copyOfRange(0, 32))}")
            println("  last 16 (tag): ${hex(ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size))}")
        } else {
            println("  ${hex(ciphertext)}")
        }
        println()

        // Header protection
        val sampleInput = unprotectedHeader.copyOfRange(pnOffset, unprotectedHeader.size) + ciphertext
        val sample = sampleInput.copyOfRange(0, 16.coerceAtMost(sampleInput.size))
        println("=== Header Protection ===")
        println("sample_input (${sampleInput.size} bytes): first 20 = ${hex(sampleInput.copyOfRange(0, 20.coerceAtMost(sampleInput.size)))}")
        println("sample (16 bytes): ${hex(sample)}")

        val mask = aesEcbEncrypt(hpKey, sample)
        println("mask (16 bytes): ${hex(mask)}")
        println()

        // Apply protection
        val protectedHeader = unprotectedHeader.copyOf()
        protectedHeader[0] = (protectedHeader[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        for (i in 0 until pnLen) {
            protectedHeader[pnOffset + i] = (protectedHeader[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }
        println("=== Protected Header (${protectedHeader.size} bytes) ===")
        println("${hex(protectedHeader)}")
        println("  first_byte: ${"%02x".format(protectedHeader[0])} (was ${"%02x".format(unprotectedHeader[0])}, mask=${"%02x".format(mask[0])})")
        println("  pn:         ${hex(protectedHeader.copyOfRange(pnOffset, pnOffset + pnLen))} (was ${hex(pn)}, mask=${hex(mask.copyOfRange(1, 1 + pnLen))})")
        println()

        // Full packet
        val packet = protectedHeader + ciphertext
        println("=== FULL PACKET (${packet.size} bytes) ===")
        // Print in 64-byte lines
        for (i in packet.indices step 64) {
            val end = (i + 64).coerceAtMost(packet.size)
            val chunk = packet.copyOfRange(i, end)
            println("%04x: %s".format(i, hex(chunk)))
        }
        println()
        println("Total: ${packet.size} bytes (must be >= 1200 per RFC 9000 §14.1)")
        assertTrue(packet.size >= 1200, "QUIC Initial must be >= 1200 bytes, got ${packet.size}")
    }

    /** Minimal QUIC varint encoder */
    private fun encodeQuicVarint(value: Long): ByteArray = when {
        value < 0x40 -> byteArrayOf(value.toByte())
        value < 0x4000 -> byteArrayOf(
            (0x40L or ((value ushr 8) and 0x3FL)).toByte(),
            (value and 0xFFL).toByte()
        )
        value < 0x40000000 -> byteArrayOf(
            (0x80L or ((value ushr 24) and 0x3FL)).toByte(),
            ((value ushr 16) and 0xFFL).toByte(),
            ((value ushr 8) and 0xFFL).toByte(),
            (value and 0xFFL).toByte()
        )
        else -> byteArrayOf(
            (0xC0L or ((value ushr 56) and 0x3FL)).toByte(),
            ((value ushr 48) and 0xFFL).toByte(),
            ((value ushr 40) and 0xFFL).toByte(),
            ((value ushr 32) and 0xFFL).toByte(),
            ((value ushr 24) and 0xFFL).toByte(),
            ((value ushr 16) and 0xFFL).toByte(),
            ((value ushr 8) and 0xFFL).toByte(),
            (value and 0xFFL).toByte()
        )
    }

    /** Decode a QUIC varint at position, returns (value, bytesConsumed) */
    private fun decodeQuicVarint(buf: ByteArray, pos: Int): Pair<Long, Int> {
        val first = buf[pos].toInt() and 0xFF
        val prefix = (first shr 6) and 0x03
        return when (prefix) {
            0 -> Pair((first and 0x3F).toLong(), 1)
            1 -> Pair(
                ((first and 0x3F).toLong() shl 8) or (buf[pos + 1].toInt() and 0xFF).toLong(),
                2
            )
            2 -> Pair(
                ((first and 0x3F).toLong() shl 24) or
                        ((buf[pos + 1].toInt() and 0xFF).toLong() shl 16) or
                        ((buf[pos + 2].toInt() and 0xFF).toLong() shl 8) or
                        (buf[pos + 3].toInt() and 0xFF).toLong(),
                4
            )
            else -> Pair(0L, 8) // simplified
        }
    }
}

// Duplicate the helpers from QuicTransport.jvm.kt since they're private
private fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray {
    val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
    return cipher.doFinal(block)
}

private fun injectQuicTransportParams(hello: ByteArray): ByteArray {
    val hsLen = ((hello[1].toInt() and 0xFF) shl 16) or ((hello[2].toInt() and 0xFF) shl 8) or (hello[3].toInt() and 0xFF)
    val body = hello.copyOfRange(4, 4 + hsLen)
    var p = 2 + 32
    val sidLen = body[p].toInt() and 0xFF; p += 1 + sidLen
    val csLen = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF); p += 2 + csLen
    val compLen = body[p].toInt() and 0xFF; p += 1 + compLen
    val extLen = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
    val extData = body.copyOfRange(p + 2, p + 2 + extLen)

    val tp = buildQuicTransportParams()
    val newExt = byteArrayOf(
        0x00, 0x39, // extension type: quic_transport_parameters
        (tp.size shr 8).toByte(), (tp.size and 0xFF).toByte() // length
    ) + tp

    val newExtData = extData + newExt
    val newExtLen = newExtData.size
    val newBody = body.copyOfRange(0, p) +
            byteArrayOf(((newExtLen ushr 8) and 0xFF).toByte(), (newExtLen and 0xFF).toByte()) +
            newExtData

    val result = ByteArray(4 + newBody.size)
    result[0] = hello[0]
    result[1] = ((newBody.size ushr 16) and 0xFF).toByte()
    result[2] = ((newBody.size ushr 8) and 0xFF).toByte()
    result[3] = (newBody.size and 0xFF).toByte()
    newBody.copyInto(result, 4)
    return result
}

private fun buildQuicTransportParams(): ByteArray {
    val b = mutableListOf<Byte>()
    fun varint(v: Long) {
        when {
            v < 0x40 -> b.add(v.toByte())
            v < 0x4000 -> { b.add((0x40L or ((v ushr 8) and 0x3FL)).toByte()); b.add((v and 0xFFL).toByte()) }
            else -> { b.add((0x80L or ((v ushr 24) and 0x3FL)).toByte()); b.add(((v ushr 16) and 0xFFL).toByte()); b.add(((v ushr 8) and 0xFFL).toByte()); b.add((v and 0xFFL).toByte()) }
        }
    }
    fun param(id: Int, value: ByteArray) { varint(id.toLong()); varint(value.size.toLong()); b.addAll(value.toList()) }
    fun param16(id: Int, v: Int) { varint(id.toLong()); varint(2L); b.add(((v ushr 8) and 0xFF).toByte()); b.add((v and 0xFF).toByte()) }
    fun paramByte(id: Int, v: Int) { varint(id.toLong()); varint(1L); b.add(v.toByte()) }

    param(0x02, byteArrayOf(100))                          // initial_max_streams_bidi
    param(0x04, byteArrayOf(0x10, 0x00, 0x00, 0x00))      // initial_max_data
    param(0x05, byteArrayOf(100))                          // initial_max_streams_uni
    param16(0x01, 30000)                                    // max_idle_timeout
    param(0x06, byteArrayOf(0x04, 0x00, 0x00))            // initial_max_stream_data_bidi_local
    param(0x07, byteArrayOf(0x04, 0x00, 0x00))            // initial_max_stream_data_bidi_remote
    param(0x08, byteArrayOf(0x04, 0x00, 0x00))            // initial_max_stream_data_uni
    param16(0x09, 65527)                                    // max_udp_payload_size
    paramByte(0x0E, 4)                                      // active_connection_id_limit

    return b.toByteArray()
}
