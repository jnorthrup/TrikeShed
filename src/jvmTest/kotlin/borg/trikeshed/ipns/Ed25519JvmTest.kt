package borg.trikeshed.ipns

import java.security.*
import java.security.interfaces.*
import kotlin.random.Random
import kotlin.test.*

/** [Ed25519] against the JDK's SunEC Ed25519: 1000 random keys and messages. */
class Ed25519JvmTest {
    @Test fun jdkKeysAndSignatures() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val random = Random(8032)
        repeat(1000) {
            val pair = generator.generateKeyPair()
            val seed = (pair.private as EdECPrivateKey).bytes.get()
            // RFC 8410 SubjectPublicKeyInfo: a fixed 12-byte prefix, then the 32-byte public key.
            val spki = pair.public.encoded
            assertEquals("302a300506032b6570032100", spki.copyOf(12).toHexString())
            val public = spki.copyOfRange(12, 44)
            val message = random.nextBytes(random.nextInt(2048))
            val jdk = Signature.getInstance("Ed25519").run { initSign(pair.private); update(message); sign() }
            val ours = Ed25519.sign(seed, message)
            assertContentEquals(public, Ed25519.publicKey(seed))
            assertContentEquals(jdk, ours)
            assertTrue(Ed25519.verify(public, message, jdk))
            assertTrue(Signature.getInstance("Ed25519").run { initVerify(pair.public); update(message); verify(ours) })
            val other = message + 1.toByte()
            assertFalse(Ed25519.verify(public, other, jdk))
            assertFalse(Signature.getInstance("Ed25519").run { initVerify(pair.public); update(other); verify(ours) })
        }
    }
}
