package borg.trikeshed.ssh

import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SshConcentricReactorTest {

    @Test
    fun testInlineChaCha20IntVectorizationRoundTrip() {
        // 256-bit key (8 Ints)
        val key = intArrayOf(
            0x03020100, 0x07060504, 0x0b0a0908, 0x0f0e0d0c,
            0x13121110, 0x17161514, 0x1b1a1918, 0x1f1e1d1c
        )
        // 96-bit nonce (3 Ints)
        val nonce = intArrayOf(0x00000000, 0x4a000000, 0x00000000)
        val plain = "Concentric SSH pure CCEK Reactor with autovectorization alignment".encodeToByteArray()
        val encrypted = ByteArray(plain.size)
        val decrypted = ByteArray(plain.size)

        SshCipher.cryptChaCha20(key, nonce, 1, plain, 0, encrypted, 0, plain.size)
        // Ensure ciphertext differs from plaintext
        assertTrue(!encrypted.contentEquals(plain))

        // Decrypt
        SshCipher.cryptChaCha20(key, nonce, 1, encrypted, 0, decrypted, 0, encrypted.size)
        assertEquals(plain.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun testInlinePoly1305IntMac() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val msg = "RFC 4253 SSH binary packet MAC".encodeToByteArray()

        val mac1 = SshCipher.SshPoly1305Int(key)
        mac1.update(msg, 0, msg.size)
        val tag1 = ByteArray(16)
        mac1.finish(tag1, 0)

        val mac2 = SshCipher.SshPoly1305Int(key)
        mac2.update(msg, 0, msg.size)
        val tag2 = ByteArray(16)
        mac2.finish(tag2, 0)

        assertTrue(tag1.contentEquals(tag2))
    }

    @Test
    fun testBinaryPacketFramingAndUnframing() {
        val scope = SshScope(
            subnet = Subnet.parse("local.mesh"),
            capability = SshCapability("127.0.0.1", 22)
        )
        val client = SshClientElement(scope)
        val payload = "ssh-userauth".encodeToByteArray()

        val framed = client.frameBinaryPacket(SshMessageNumber.SERVICE_REQUEST, payload)
        // Must be multiple of 8
        assertEquals(0, framed.size % 8)

        val unframed = client.unframeBinaryPacket(framed)
        assertNotNull(unframed)
        assertEquals(SshMessageNumber.SERVICE_REQUEST, unframed.messageNumber)
        assertEquals("ssh-userauth", unframed.payload.decodeToString())
    }

    @Test
    fun testConcentricReactorInvocationAndDrain() = runBlocking {
        val rootSubnet = Subnet.parse("local")
        val scope = SshScope(
            subnet = rootSubnet,
            capability = SshCapability("ssh.gateway.lan", 2222)
        )
        val client = SshClientElement(scope)

        // Valid concentric request from inner subnet "local.node1"
        val innerSubnet = Subnet.parse("local.node1")
        val validNuid = nuid(scope.capability, Nonce.RandomBytes(16), innerSubnet)
        val action = validNuid j ("kexinit" j "curve25519-sha256".encodeToByteArray())

        val result = client.invoke(action)
        assertEquals("ok", result.b.a)
        val packet = client.unframeBinaryPacket(result.b.b)
        assertNotNull(packet)
        assertEquals(SshMessageNumber.KEXINIT, packet.messageNumber)

        // Invalid subnet outside concentric hierarchy
        val foreignSubnet = Subnet.parse("wan.foreign")
        val invalidNuid = nuid(scope.capability, Nonce.RandomBytes(16), foreignSubnet)
        val rejectedAction = invalidNuid j ("kexinit" j ByteArray(0))
        val rejectedResult = client.invoke(rejectedAction)
        assertEquals("error", rejectedResult.b.a)

        // Test CCEK drain
        client.drain()
        assertTrue(!client.isConnected())
    }
}
