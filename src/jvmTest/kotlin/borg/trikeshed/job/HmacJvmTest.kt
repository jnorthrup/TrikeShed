package borg.trikeshed.job

import javax.crypto.*
import javax.crypto.spec.*
import kotlin.random.Random
import kotlin.test.*

/** [hmacSha256], [hmacSha512] against javax.crypto.Mac; [hkdfExtract], [hkdfExpand] against the JDK 25 KDF HKDF-SHA256. */
class HmacJvmTest {
    @Test fun jdkMac() {
        val random = Random(2104)
        repeat(1000) {
            val key = random.nextBytes(random.nextInt(1, 300))
            val text = random.nextBytes(random.nextInt(0, 1000))
            assertContentEquals(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(text) }, hmacSha256(key, text))
            assertContentEquals(Mac.getInstance("HmacSHA512").run { init(SecretKeySpec(key, "HmacSHA512")); doFinal(text) }, hmacSha512(key, text))
        }
    }

    @Test fun jdkHkdf() {
        val random = Random(5869)
        val kdf = KDF.getInstance("HKDF-SHA256")
        repeat(300) {
            val ikm = random.nextBytes(random.nextInt(1, 100))
            val salt = random.nextBytes(random.nextInt(1, 100))
            val info = random.nextBytes(random.nextInt(0, 100))
            val length = random.nextInt(1, 255 * 32 + 1)
            val prk = kdf.deriveData(HKDFParameterSpec.ofExtract().addIKM(ikm).addSalt(salt).extractOnly())
            assertContentEquals(prk, hkdfExtract(salt, ikm))
            assertContentEquals(kdf.deriveData(HKDFParameterSpec.expandOnly(SecretKeySpec(prk, "HKDF"), info, length)), hkdfExpand(prk, info, length))
        }
    }
}
