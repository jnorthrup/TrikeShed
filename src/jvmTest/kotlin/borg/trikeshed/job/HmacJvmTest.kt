package borg.trikeshed.job

import javax.crypto.*
import javax.crypto.spec.*
import kotlin.random.Random
import kotlin.test.*

/** [hmacSha256], [hmacSha384], [hmacSha512] against javax.crypto.Mac; [hkdfExtract], [hkdfExpand] against the JDK 25 KDF HKDF-SHA256 and HKDF-SHA384. */
class HmacJvmTest {
    @Test fun jdkMac() {
        val random = Random(2104)
        repeat(1000) {
            val key = random.nextBytes(random.nextInt(1, 300))
            val text = random.nextBytes(random.nextInt(0, 1000))
            assertContentEquals(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(text) }, hmacSha256(key, text))
            assertContentEquals(Mac.getInstance("HmacSHA384").run { init(SecretKeySpec(key, "HmacSHA384")); doFinal(text) }, hmacSha384(key, text))
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

    @Test fun jdkHkdfSha384() {
        val random = Random(8446)
        val kdf = KDF.getInstance("HKDF-SHA384")
        repeat(300) {
            val ikm = random.nextBytes(random.nextInt(1, 100))
            val salt = random.nextBytes(random.nextInt(1, 100))
            val info = random.nextBytes(random.nextInt(0, 100))
            val length = random.nextInt(1, 255 * 48 + 1)
            val prk = kdf.deriveData(HKDFParameterSpec.ofExtract().addIKM(ikm).addSalt(salt).extractOnly())
            assertContentEquals(prk, hkdfExtract(salt, ikm, ::hmacSha384))
            assertContentEquals(kdf.deriveData(HKDFParameterSpec.expandOnly(SecretKeySpec(prk, "HKDF"), info, length)), hkdfExpand(prk, info, length, ::hmacSha384))
        }
    }

    @Test fun jdkHkdfBoundaries() {
        val prk = ByteArray(64) { it.toByte() }
        val info = byteArrayOf(3, 1, 4)
        for (hmac in listOf(::hmacSha256, ::hmacSha384)) {
            val width = hmac(prk, info).size
            val kdf = KDF.getInstance("HKDF-SHA${width * 8}")
            for (length in listOf(1, width - 1, width, width + 1, 255 * width)) {
                assertContentEquals(
                    kdf.deriveData(HKDFParameterSpec.expandOnly(SecretKeySpec(prk, "HKDF"), info, length)),
                    hkdfExpand(prk, info, length, hmac),
                )
            }
            assertFailsWith<IllegalArgumentException> { hkdfExpand(prk, info, Int.MAX_VALUE, hmac) }
        }
    }
}
