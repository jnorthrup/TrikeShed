package borg.trikeshed.job

import javax.crypto.*
import javax.crypto.spec.*
import kotlin.random.Random
import kotlin.test.*

/** [ChaCha20Poly1305] against the JDK's ChaCha20-Poly1305 cipher: equal output, each opening the other's, and tag mismatches rejected by both. */
class ChaCha20Poly1305JvmTest {
    fun jdk(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").run {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            updateAAD(aad)
            doFinal(input)
        }

    @Test fun jdkCipher() {
        val random = Random(8439)
        repeat(1000) {
            val key = random.nextBytes(32)
            val nonce = random.nextBytes(12)
            val aad = random.nextBytes(random.nextInt(0, 100))
            val plaintext = random.nextBytes(random.nextInt(0, 2000))
            val sealed = jdk(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext)
            assertContentEquals(sealed, ChaCha20Poly1305.encrypt(key, nonce, aad, plaintext))
            assertContentEquals(plaintext, ChaCha20Poly1305.decrypt(key, nonce, aad, sealed))
            assertContentEquals(plaintext, jdk(Cipher.DECRYPT_MODE, key, nonce, aad, ChaCha20Poly1305.encrypt(key, nonce, aad, plaintext)))
            val forged = sealed.copyOf().also { val i = random.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte() }
            assertNull(ChaCha20Poly1305.decrypt(key, nonce, aad, forged))
            assertFailsWith<AEADBadTagException> { jdk(Cipher.DECRYPT_MODE, key, nonce, aad, forged) }
        }
    }
}
