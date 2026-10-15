package borg.trikeshed.job

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** [Sha3] and [Sha3Ffi] against the JDK's SHA3-224/256/384/512 at every length to 1024 bytes, whole and in two parts. */
class Sha3JvmTest {
    @Test fun jdkSha3() {
        val random = Random(202)
        for (size in 0..1024) {
            val message = random.nextBytes(size)
            val cut = random.nextInt(size + 1)
            for (bytes in intArrayOf(28, 32, 48, 64)) {
                val jdk = MessageDigest.getInstance("SHA3-${bytes * 8}").digest(message)
                assertContentEquals(jdk, Sha3(bytes).update(message).digest())
                assertContentEquals(jdk, Sha3(bytes).update(message, 0, cut).update(message, cut, size - cut).digest())
                assertContentEquals(jdk, Sha3Ffi.digest(message, size = bytes))
                assertContentEquals(MessageDigest.getInstance("SHA3-${bytes * 8}").digest(message.copyOfRange(cut, size)),
                    Sha3Ffi.digest(message, cut, size - cut, bytes))
            }
        }
    }
}
