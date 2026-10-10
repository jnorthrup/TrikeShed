package borg.trikeshed.job

import java.math.BigInteger
import java.security.*
import java.util.Base64 as JdkBase64
import kotlin.random.Random
import kotlin.test.*

/**
 * [RS256] against the JDK's SHA256withRSA over throwaway keys. PKCS#1 v1.5 signing is deterministic, so
 * the signatures must be identical; each side verifies the other's, and a changed message or signature fails.
 */
class RS256JvmTest {
    fun magnitude(value: BigInteger): ByteArray = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    fun proof(bits: Int) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + JdkBase64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val key = RSAPrivateKey.pem(pem)
        val jdkPublic = pair.public as java.security.interfaces.RSAPublicKey
        val public = RSAPublicKey(magnitude(jdkPublic.modulus), magnitude(jdkPublic.publicExponent))
        assertContentEquals(public.modulus, key.modulus)
        assertContentEquals(public.publicExponent, key.publicExponent)
        val random = Random(bits)
        repeat(8) {
            val message = random.nextBytes(random.nextInt(0, 4096))
            val jdk = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(message); sign() }
            val ours = RS256.sign(key, message)
            assertContentEquals(jdk, ours)
            assertTrue(RS256.verify(public, message, jdk))
            assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(pair.public); update(message); verify(ours) })
            assertFalse(RS256.verify(public, message + 0, jdk))
            val flipped = jdk.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
            assertFalse(RS256.verify(public, message, flipped))
            assertFalse(Signature.getInstance("SHA256withRSA").run { initVerify(pair.public); update(message + 0); verify(ours) })
        }
    }

    @Test fun jdk2048() = proof(2048)

    @Test fun jdk4096() = proof(4096)
}
