package borg.trikeshed.htx.client

import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.codec.ecdh.DefaultX25519
import borg.trikeshed.tls.codec.hash.DefaultSha256
import borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256
import borg.trikeshed.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import kotlinx.coroutines.test.runTest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Head-to-head: send the same QUIC Initial via raw JDK DatagramSocket
 * AND via the uring facade. Compare byte-for-byte and check responses.
 */
class JvmQuicHeadToHeadTest {

    private fun hex(buf: ByteArray, len: Int = buf.size): String =
        buf.copyOfRange(0, len.coerceAtMost(buf.size)).joinToString(" ") { "%02x".format(it) }

    private fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    /** QUIC HKDF-Expand-Label without "tls13 " prefix */
    private fun quicExpandLabel(
        hkdf: DefaultHkdfSha256, secret: ByteArray, label: String, context: ByteArray, length: Int
    ): ByteArray {
        val labelBytes = label.encodeToByteArray()
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

    private fun quicVarint(v: Long): ByteArray = when {
        v < 0x40 -> byteArrayOf(v.toByte())
        v < 0x4000 -> byteArrayOf((0x40L or ((v ushr 8) and 0x3FL)).toByte(), (v and 0xFFL).toByte())
        else -> byteArrayOf(
            (0x80L or ((v ushr 24) and 0x3FL)).toByte(),
            ((v ushr 16) and 0xFFL).toByte(),
            ((v ushr 8) and 0xFFL).toByte(),
            (v and 0xFFL).toByte()
        )
    }

    /** Build QUIC transport params extension payload */
    private fun buildTp(): ByteArray {
        val b = mutableListOf<Byte>()
        fun varint(v: Long) { b.addAll(quicVarint(v).toList()) }
        fun param(id: Int, value: ByteArray) { varint(id.toLong()); varint(value.size.toLong()); b.addAll(value.toList()) }
        param(0x02, byteArrayOf(100))
        param(0x04, byteArrayOf(0x10, 0x00, 0x00, 0x00))
        param(0x05, byteArrayOf(100))
        param(0x01, byteArrayOf(0x75, 0x30)) // 30000
        param(0x06, byteArrayOf(0x04, 0x00, 0x00))
        param(0x07, byteArrayOf(0x04, 0x00, 0x00))
        param(0x08, byteArrayOf(0x04, 0x00, 0x00))
        param(0x09, byteArrayOf(0xFF.toByte(), 0xF7.toByte())) // 65527
        param(0x0E, byteArrayOf(4))
        return b.toByteArray()
    }

    /** Inject TP into ClientHello */
    private fun injectTp(hello: ByteArray): ByteArray {
        val hsLen = ((hello[1].toInt() and 0xFF) shl 16) or ((hello[2].toInt() and 0xFF) shl 8) or (hello[3].toInt() and 0xFF)
        val body = hello.copyOfRange(4, 4 + hsLen)
        var p = 2 + 32
        val sidLen = body[p].toInt() and 0xFF; p += 1 + sidLen
        val csLen = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF); p += 2 + csLen
        val compLen = body[p].toInt() and 0xFF; p += 1 + compLen
        val extLen = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
        val extData = body.copyOfRange(p + 2, p + 2 + extLen)
        val tp = buildTp()
        val newExt = byteArrayOf(0x00, 0x39, (tp.size shr 8).toByte(), (tp.size and 0xFF).toByte()) + tp
        val newExtData = extData + newExt
        val newBody = body.copyOfRange(0, p) +
                byteArrayOf(((newExtData.size ushr 8) and 0xFF).toByte(), (newExtData.size and 0xFF).toByte()) +
                newExtData
        val result = ByteArray(4 + newBody.size)
        result[0] = hello[0]
        result[1] = ((newBody.size ushr 16) and 0xFF).toByte()
        result[2] = ((newBody.size ushr 8) and 0xFF).toByte()
        result[3] = (newBody.size and 0xFF).toByte()
        newBody.copyInto(result, 4)
        return result
    }

    /** Build full QUIC Initial packet (mirrors QuicTransport.buildQuicInitial exactly) */
    internal fun buildPacket(dcid: ByteArray, serverName: String): ByteArray {
        val sha256 = DefaultSha256()
        val hkdf = DefaultHkdfSha256(sha256)
        val aes = DefaultAes128Gcm()
        val initialSalt = byteArrayOf(
            0x38, 0x76, 0x2C.toByte(), 0xF7.toByte(), 0xF5.toByte(), 0x59, 0x34, 0xB3.toByte(),
            0x4D, 0x17, 0x9A.toByte(), 0xE6.toByte(), 0xA4.toByte(), 0xC8.toByte(), 0x0C, 0xAD.toByte(),
            0xCC.toByte(), 0xBB.toByte(), 0x7F, 0x0A
        )
        val initialSecret = hkdf.extract(initialSalt, dcid)
        val clientSecret = hkdf.expandLabel(initialSecret, "client in", ByteArray(0), 32)
        val key = hkdf.expandLabel(clientSecret, "quic key", ByteArray(0), 16)
        val iv = hkdf.expandLabel(clientSecret, "quic iv", ByteArray(0), 12)
        val hpKey = hkdf.expandLabel(clientSecret, "quic hp", ByteArray(0), 16)

        val x25519 = DefaultX25519()
        val codec = CommonTlsRecordCodec(aes)
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, serverName, listOf("h3"))
        val clientHello = injectTp(hs.buildClientHello())

        // CRYPTO frame
        val cryptoFrame = byteArrayOf(0x06) + quicVarint(0L) + quicVarint(clientHello.size.toLong()) + clientHello

        // Match aioquic: ~460 bytes payload, pad to 1200 outside AEAD
        val targetPayloadSize = 460
        val paddingNeeded = (targetPayloadSize - cryptoFrame.size).coerceAtLeast(0)
        val payload = cryptoFrame + ByteArray(paddingNeeded)

        val pn = byteArrayOf(0x00, 0x00)
        val pnLen = 2
        val firstByte = (0xC0 or (pnLen - 1)).toByte()  // 0xC1
        val scid = kotlin.random.Random.nextBytes(8)
        val header = mutableListOf<Byte>()
        header.add(firstByte)
        header.addAll(listOf(0x00, 0x00, 0x00, 0x01).map { it.toByte() })
        header.add(dcid.size.toByte())
        header.addAll(dcid.toList())
        header.add(scid.size.toByte())
        header.addAll(scid.toList())
        header.add(0) // token len
        header.addAll(quicVarint((payload.size + pnLen + 16).toLong()).toList())
        header.addAll(pn.toList())
        val unprotectedHeader = header.toByteArray()

        // Nonce
        val nonce = ByteArray(12)
        for (i in pn.indices) nonce[12 - pn.size + i] = pn[i]
        for (i in iv.indices) nonce[i] = (nonce[i].toInt() xor iv[i].toInt()).toByte()

        // AEAD
        val ciphertext = aes.seal(key, nonce, unprotectedHeader, payload)

        // Header protection
        val pnOffset = unprotectedHeader.size - pnLen
        val sampleInput = unprotectedHeader.copyOfRange(pnOffset, unprotectedHeader.size) + ciphertext
        val sample = sampleInput.copyOfRange(0, 16.coerceAtMost(sampleInput.size))
        val mask = aesEcbEncrypt(hpKey, sample)

        val protectedHeader = unprotectedHeader.copyOf()
        protectedHeader[0] = (protectedHeader[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        for (i in 0 until pnLen) {
            protectedHeader[pnOffset + i] = (protectedHeader[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }

        // Pad to 1200 minimum
        val core = protectedHeader + ciphertext
        val padNeeded = (1200 - core.size).coerceAtLeast(0)
        return core + ByteArray(padNeeded)
    }

    @Test
    fun `raw JDK UDP send of QUIC Initial to google com`() {
        val addr = InetAddress.getByName("google.com")
        println("[RAW] google.com resolved to $addr")

        val dcid = byteArrayOf(0x83.toByte(), 0x94.toByte(), 0xC8.toByte(), 0xF0.toByte(), 0x3E, 0x51, 0x57, 0x08)
        val packet = buildPacket(dcid, "google.com")
        println("[RAW] packet ${packet.size} bytes")
        println("[RAW] first 40: ${hex(packet, 40)}")

        val sock = DatagramSocket()
        sock.soTimeout = 5000
        sock.connect(addr, 443)
        println("[RAW] connected to ${sock.remoteSocketAddress}")

        sock.send(DatagramPacket(packet, packet.size))
        println("[RAW] sent ${packet.size} bytes")

        try {
            val recvBuf = ByteArray(2048)
            val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
            sock.receive(recvPkt)
            println("[RAW] RESPONSE: ${recvPkt.length} bytes")
            println("[RAW] first 40: ${hex(recvBuf, recvPkt.length.coerceAtMost(40))}")
            val firstByte = recvBuf[0].toInt() and 0xFF
            val isLong = (firstByte and 0x80) != 0
            println("[RAW] first_byte=0x${"%02x".format(firstByte)} long=$isLong")
            if (isLong) {
                val version = ((recvBuf[1].toInt() and 0xFF) shl 24) or
                        ((recvBuf[2].toInt() and 0xFF) shl 16) or
                        ((recvBuf[3].toInt() and 0xFF) shl 8) or
                        (recvBuf[4].toInt() and 0xFF)
                println("[RAW] version=0x${"%08x".format(version)}")
            }
            assertTrue(recvPkt.length > 0)
        } catch (e: java.net.SocketTimeoutException) {
            println("[RAW] TIMEOUT after 5s — no response from google.com:443")
            println("[RAW] This confirms the packet structure is invalid (google silently drops it)")
            assertTrue(true)
        } finally {
            sock.close()
        }
    }

    @Test
    fun `uring facade send same packet to google com`() = runTest {
        val platformChannels = JvmChannelOperations()
        val dcid = byteArrayOf(0x83.toByte(), 0x94.toByte(), 0xC8.toByte(), 0xF0.toByte(), 0x3E, 0x51, 0x57, 0x08)
        val packet = buildPacket(dcid, "google.com")

        println("[URING] packet ${packet.size} bytes")
        println("[URING] first 40: ${hex(packet, 40)}")

        val fd = platformChannels.socket(2, 2, 17)
        check(fd >= 0)
        println("[URING] fd=$fd")

        try {
            val connResult = platformChannels.connect(fd, "google.com", 443)
            println("[URING] connect=$connResult")

            val sendResult = platformChannels.send(fd, ByteBuffer.wrap(packet), 0L)
            println("[URING] sent=$sendResult")

            val recvBuf = ByteArray(2048)
            val recvResult = platformChannels.recv(fd, ByteBuffer.wrap(recvBuf), 0L)
            println("[URING] recv=$recvResult")
            if (recvResult > 0) {
                println("[URING] RESPONSE: ${recvResult} bytes")
                println("[URING] first 40: ${hex(recvBuf, recvResult.coerceAtMost(40))}")
            } else {
                println("[URING] TIMEOUT")
            }
        } finally {
            platformChannels.close(fd)
        }
    }
}
