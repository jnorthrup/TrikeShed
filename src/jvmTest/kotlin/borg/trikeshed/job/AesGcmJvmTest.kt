package borg.trikeshed.job

import javax.crypto.*
import javax.crypto.spec.*
import kotlin.random.Random
import kotlin.test.*

/** [Aes] against the JDK's AES/ECB, [AesGcm] against AES/GCM/NoPadding: equal output, each opening the other's, flipped bits rejected by both. */
class AesGcmJvmTest {
    fun jdk(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(input)
        }

    @Test fun jdkBlocks() {
        val random = Random(197)
        repeat(3000) {
            val key = random.nextBytes(16 + 8 * (it % 3))
            val block = random.nextBytes(16)
            val jdk = Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block) }
            assertContentEquals(jdk, Aes(key).encryptBlock(block))
        }
    }

    @Test fun jdkCipher() {
        val random = Random(38)
        repeat(1000) {
            val key = random.nextBytes(16 + 8 * (it % 3))
            val nonce = random.nextBytes(12)
            val aad = random.nextBytes(random.nextInt(0, 100))
            val plaintext = random.nextBytes(random.nextInt(0, 2000))
            val aead = AesGcm(key)
            val sealed = jdk(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext)
            assertContentEquals(sealed, aead.encrypt(nonce, aad, plaintext))
            assertContentEquals(plaintext, aead.decrypt(nonce, aad, sealed))
            assertContentEquals(plaintext, jdk(Cipher.DECRYPT_MODE, key, nonce, aad, aead.encrypt(nonce, aad, plaintext)))
            val forged = sealed.copyOf().also { val i = random.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte() }
            assertNull(aead.decrypt(nonce, aad, forged))
            assertFailsWith<AEADBadTagException> { jdk(Cipher.DECRYPT_MODE, key, nonce, aad, forged) }
        }
    }
}
