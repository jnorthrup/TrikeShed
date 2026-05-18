package borg.trikeshed.tls

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class TlsElementTest {
    @Test
    fun openClose() = runBlocking {
        val elem = TlsElement()
        elem.open()
        assertTrue(true)
        elem.close()
    }

    @Test
    fun testDefaultX25519Consistency() {
        val x = borg.trikeshed.tls.codec.ecdh.DefaultX25519()
        val alice = x.generateKeyPair()
        val bob = x.generateKeyPair()
        val secretA = x.sharedSecret(alice.privateKey, bob.publicKey)
        val secretB = x.sharedSecret(bob.privateKey, alice.publicKey)
        assertTrue(secretA.contentEquals(secretB), "DefaultX25519 should be consistent!")
    }

    @Test
    fun testDefaultAes128Gcm() {
        val gcm = borg.trikeshed.tls.codec.aead.DefaultAes128Gcm()
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = byteArrayOf(1, 2, 3)
        val pt = byteArrayOf(4, 5, 6, 7, 8)
        val ct = gcm.seal(key, nonce, aad, pt)
        val decrypted = gcm.open(key, nonce, aad, ct)
        assertNotNull(decrypted)
        assertTrue(pt.contentEquals(decrypted))
    }
}
