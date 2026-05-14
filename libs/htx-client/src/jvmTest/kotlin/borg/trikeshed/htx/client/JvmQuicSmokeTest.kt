package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.tls.codec.hash.DefaultSha256
import borg.trikeshed.userspace.nio.tls.codec.kdf.DefaultHkdfSha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmQuicSmokeTest {

    /** RFC 9001 Appendix A.1 key derivation test vector */
    @Test
    fun `RFC 9001 initial key derivation matches test vector`() {
        val sha256 = DefaultSha256()
        val hkdf = DefaultHkdfSha256(sha256)

        val cid = byteArrayOf(0x83.toByte(), 0x94.toByte(), 0xC8.toByte(), 0xF0.toByte(), 0x3E, 0x51, 0x57, 0x08)
        val initialSalt = byteArrayOf(
            0x38, 0x76, 0x2C.toByte(), 0xF7.toByte(), 0xF5.toByte(), 0x59, 0x34, 0xB3.toByte(),
            0x4D, 0x17, 0x9A.toByte(), 0xE6.toByte(), 0xA4.toByte(), 0xC8.toByte(), 0x0C, 0xAD.toByte(),
            0xCC.toByte(), 0xBB.toByte(), 0x7F, 0x0A
        )

        val initialSecret = hkdf.extract(initialSalt, cid)
        println("initial_secret: " + initialSecret.joinToString("") { "%02x".format(it) })

        val clientSecret = hkdf.expandLabel(initialSecret, "client in", ByteArray(0), 32)
        val clientKey = hkdf.expandLabel(clientSecret, "quic key", ByteArray(0), 16)
        val clientIv = hkdf.expandLabel(clientSecret, "quic iv", ByteArray(0), 12)
        val clientHp = hkdf.expandLabel(clientSecret, "quic hp", ByteArray(0), 16)

        println("client_key: " + clientKey.joinToString("") { "%02x".format(it) })
        println("client_iv:  " + clientIv.joinToString("") { "%02x".format(it) })
        println("client_hp:  " + clientHp.joinToString("") { "%02x".format(it) })

        // RFC 9001 Appendix A expected values
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", clientKey.joinToString("") { "%02x".format(it) })
        assertEquals("fa044b2f42a3fd3b46fb255c", clientIv.joinToString("") { "%02x".format(it) })
        assertEquals("9f50449e04a0e810283a1e9933adedd2", clientHp.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `QUIC Initial to google com via uring facade`() = runTest {
        val elem = HtxElement()
        elem.registerTransport(HtxTransport.QUIC, createQuicHandler())
        val resp = elem.request("GET", "quic://google.com:443")
        println("[QUIC SMOKE] status=${resp.status} body=${resp.body}")
        assertTrue(
            resp.status == 200 || resp.status == 504,
            "Expected 200 or 504 but got ${resp.status}: ${resp.body}"
        )
    }
}
