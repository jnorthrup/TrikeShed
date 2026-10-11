package borg.trikeshed.ipns

import kotlin.test.*

/** RFC 7748 5.2 and 6.1 X25519 vectors, the first iteration of 5.2's chain, and the small-order u-coordinates yielding zero. */
class X25519Test {
    fun hex(text: String) = text.hexToByteArray()

    @Test fun section52() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            x25519(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")).toHexString(),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            x25519(hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"), hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")).toHexString(),
        )
        assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", x25519(X25519_BASE_POINT, X25519_BASE_POINT).toHexString())
    }

    @Test fun section61DiffieHellman() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = x25519(alice, X25519_BASE_POINT)
        val bobPublic = x25519(bob, X25519_BASE_POINT)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePublic.toHexString())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPublic.toHexString())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, x25519(alice, bobPublic).toHexString())
        assertEquals(shared, x25519(bob, alicePublic).toHexString())
    }

    /** u = 0, 1, the order-8 points and p - 1, p, p + 1 (libsodium's blocklist): every scalar maps them to 0. */
    @Test fun smallOrderPointsGiveZero() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        for (u in listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )) assertTrue(x25519(scalar, hex(u)).all { it == 0.toByte() }, u)
    }
}
