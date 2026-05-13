package borg.trikeshed.htx.client

import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.codec.ecdh.DefaultX25519
import borg.trikeshed.tls.codec.hash.DefaultSha256
import borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256
import borg.trikeshed.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmQuicDiagTest {

    @Test
    fun `dump QUIC Initial packet header bytes`() {
        val sha256 = DefaultSha256()
        val hkdf = DefaultHkdfSha256(sha256)
        val aes = DefaultAes128Gcm()
        val x25519 = DefaultX25519()
        val codec = CommonTlsRecordCodec(aes)

        // Use fixed DCID for reproducibility
        val dcid = ByteArray(8) { (it + 1).toByte() }
        val serverName = "google.com"

        // Access the same buildQuicInitial logic via reflection or just inline the check
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, serverName, listOf("h3"))
        val clientHelloRaw = hs.buildClientHello()

        println("=== ClientHello raw (${clientHelloRaw.size} bytes) ===")
        println("first 20: " + clientHelloRaw.copyOfRange(0, 20).joinToString(" ") { "%02x".format(it) })

        // Check handshake type
        assertEquals(0x01, clientHelloRaw[0].toInt(), "Handshake type should be ClientHello (0x01)")

        // Check handshake length
        val hsLen = ((clientHelloRaw[1].toInt() and 0xFF) shl 16) or
                ((clientHelloRaw[2].toInt() and 0xFF) shl 8) or
                (clientHelloRaw[3].toInt() and 0xFF)
        println("handshake length: $hsLen")
        assertEquals(clientHelloRaw.size - 4, hsLen, "Handshake length mismatch")

        // Check TLS version in body
        val tlsVersion = ((clientHelloRaw[4].toInt() and 0xFF) shl 8) or (clientHelloRaw[5].toInt() and 0xFF)
        println("TLS legacy version: 0x${"%04x".format(tlsVersion)}")
        assertEquals(0x0303, tlsVersion, "Legacy version should be 0x0303")

        // Check extensions for SNI and ALPN
        var p = 6 + 32 // skip version + random
        val sidLen = clientHelloRaw[p].toInt() and 0xFF; p += 1 + sidLen
        val csLen = ((clientHelloRaw[p].toInt() and 0xFF) shl 8) or (clientHelloRaw[p + 1].toInt() and 0xFF); p += 2 + csLen
        val compLen = clientHelloRaw[p].toInt() and 0xFF; p += 1 + compLen

        val extLen = ((clientHelloRaw[p].toInt() and 0xFF) shl 8) or (clientHelloRaw[p + 1].toInt() and 0xFF)
        println("extensions block: $extLen bytes starting at offset $p")
        p += 2

        val extEnd = p + extLen
        while (p < extEnd) {
            val extType = ((clientHelloRaw[p].toInt() and 0xFF) shl 8) or (clientHelloRaw[p + 1].toInt() and 0xFF)
            val extDataLen = ((clientHelloRaw[p + 2].toInt() and 0xFF) shl 8) or (clientHelloRaw[p + 3].toInt() and 0xFF)
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
            println("  ext $extType ($name): $extDataLen bytes")
            p += 4 + extDataLen
        }
    }
}
