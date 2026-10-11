package borg.trikeshed.job

import java.math.BigInteger
import java.security.*
import java.security.spec.*
import kotlin.random.Random
import kotlin.test.*

/**
 * [RSASSA_PKCS1_v1_5] and [RSASSA_PSS] verification against the JDK's SHA{256,384,512}withRSA and RSASSA-PSS
 * (MGF1 with the message hash, salt as long as the hash) over throwaway keys, a 2049-bit modulus among them
 * so EMSA-PSS runs with emLen = k - 1; changed messages, signatures and salt lengths are refused.
 */
class RsassaJvmTest {
    fun magnitude(value: BigInteger): ByteArray = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    val hashes = listOf(
        Triple("SHA-256", RSASSA_PKCS1_v1_5.SHA256) { m: ByteArray -> Sha256Pure.digest(m) },
        Triple("SHA-384", RSASSA_PKCS1_v1_5.SHA384) { m: ByteArray -> Sha384Pure.digest(m) },
        Triple("SHA-512", RSASSA_PKCS1_v1_5.SHA512) { m: ByteArray -> Sha512Pure.digest(m) },
    )

    fun proof(bits: Int) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
        val jdkPublic = pair.public as java.security.interfaces.RSAPublicKey
        val key = RSAPublicKey(magnitude(jdkPublic.modulus), magnitude(jdkPublic.publicExponent))
        val random = Random(bits)
        for ((name, digestInfo, hash) in hashes) {
            val message = random.nextBytes(random.nextInt(0, 2000))
            val pkcs1 = Signature.getInstance(name.replace("-", "") + "withRSA").run { initSign(pair.private); update(message); sign() }
            assertTrue(RSASSA_PKCS1_v1_5.verify(key, digestInfo, hash(message), pkcs1), "$bits $name PKCS#1")
            assertFalse(RSASSA_PKCS1_v1_5.verify(key, digestInfo, hash(message + 0), pkcs1))
            assertFalse(RSASSA_PKCS1_v1_5.verify(key, digestInfo, hash(message), pkcs1.copyOf().also { it[7] = (it[7].toInt() xor 4).toByte() }))
            val hLen = hash(ByteArray(0)).size
            val pss = Signature.getInstance("RSASSA-PSS").run {
                setParameter(PSSParameterSpec(name, "MGF1", MGF1ParameterSpec(name), hLen, 1))
                initSign(pair.private)
                update(message)
                sign()
            }
            assertTrue(RSASSA_PSS.verify(key, hash, message, pss), "$bits $name PSS")
            assertFalse(RSASSA_PSS.verify(key, hash, message + 0, pss))
            assertFalse(RSASSA_PSS.verify(key, hash, message, pss.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }))
            assertFalse(RSASSA_PKCS1_v1_5.verify(key, digestInfo, hash(message), pss))
            assertFalse(RSASSA_PSS.verify(key, hash, message, pkcs1))
            val shortSalt = Signature.getInstance("RSASSA-PSS").run {
                setParameter(PSSParameterSpec(name, "MGF1", MGF1ParameterSpec(name), hLen - 1, 1))
                initSign(pair.private)
                update(message)
                sign()
            }
            assertFalse(RSASSA_PSS.verify(key, hash, message, shortSalt))
        }
    }

    @Test fun jdk2048() = proof(2048)

    @Test fun jdk2049() = proof(2049)

    @Test fun jdk3072() = proof(3072)

    @Test fun jdk4096() = proof(4096)
}
