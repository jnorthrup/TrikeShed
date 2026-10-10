package borg.trikeshed.job

import java.security.*
import kotlin.random.Random
import kotlin.test.*

/** [Sha512Pure] against the JDK's SHA-512 at every length to 1024 bytes, whole and in two parts. */
class Sha512JvmTest {
    @Test fun jdkSha512() {
        val random = Random(512)
        for (size in 0..1024) {
            val message = random.nextBytes(size)
            val cut = random.nextInt(size + 1)
            val jdk = MessageDigest.getInstance("SHA-512").digest(message)
            assertContentEquals(jdk, Sha512Pure.digest(message))
            assertContentEquals(jdk, Sha512Pure.digest(message.copyOf(cut), message.copyOfRange(cut, size)))
        }
    }
}
