package borg.trikeshed.ipns

import java.math.BigInteger
import java.security.*
import java.security.spec.*
import javax.crypto.KeyAgreement
import kotlin.random.Random
import kotlin.test.*

/** [x25519] against the JDK's XDH key agreement over random scalars and u-coordinates, and RFC 7748 5.2 after 1,000 iterations. */
class X25519JvmTest {
    /** The JDK's X25519(k, u): XECPublicKeySpec takes u as an integer, bit 255 masked as RFC 7748 decodeUCoordinate does. */
    fun jdk(k: ByteArray, u: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("XDH")
        val private = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, k))
        val public = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, u.reversedArray().also { it[0] = (it[0].toInt() and 0x7f).toByte() })))
        return KeyAgreement.getInstance("XDH").run { init(private); doPhase(public, true); generateSecret() }
    }

    @Test fun jdkXdh() {
        val random = Random(7748)
        repeat(300) {
            val k = random.nextBytes(32)
            val u = random.nextBytes(32)
            assertContentEquals(jdk(k, u), x25519(k, u))
        }
    }

    @Test fun jdkKeyPairs() {
        val generator = KeyPairGenerator.getInstance("X25519")
        repeat(50) {
            val pair = generator.generateKeyPair()
            val k = (pair.private as java.security.interfaces.XECPrivateKey).scalar.get()
            val u = (pair.public as java.security.interfaces.XECPublicKey).u
            val encoded = u.toByteArray().reversedArray().copyOf(32)
            assertContentEquals(encoded, x25519(k, X25519_BASE_POINT))
        }
    }

    @Test fun section52Iterated1000() {
        var k = X25519_BASE_POINT.copyOf()
        var u = X25519_BASE_POINT.copyOf()
        repeat(1000) {
            val r = x25519(k, u)
            u = k
            k = r
        }
        assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", k.toHexString())
    }
}
