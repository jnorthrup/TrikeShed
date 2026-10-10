package borg.trikeshed.job

import kotlin.test.*

/** FIPS 180-4 SHA-512 example vectors. */
class Sha512Test {
    val vectors = listOf(
        "abc".encodeToByteArray() to
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
        ByteArray(0) to
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray() to
            "204a8fc6dda82f0a0ced7beb8e08a41657c16ef468b228a8279be331a703c33596fd15c13b1b07f9aa1d3bea57789ca031ad85c7a71dd70354ec631238ca3445",
        ("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
            "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu").encodeToByteArray() to
            "8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909",
        ByteArray(1_000_000) { 'a'.code.toByte() } to
            "e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973ebde0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b",
    )

    @Test fun fips180Vectors() {
        for ((message, digest) in vectors) assertEquals(digest, Sha512Pure.digest(message).toHexString())
    }

    @Test fun partsHashAsTheirConcatenation() {
        val (message, digest) = vectors[3]
        val twice = message + message + message
        val expected = Sha512Pure.digest(twice)
        for (cut in 0..twice.size) {
            if (cut <= message.size) assertEquals(digest, Sha512Pure.digest(message.copyOf(cut), message.copyOfRange(cut, message.size)).toHexString())
            assertContentEquals(expected, Sha512Pure.digest(twice.copyOf(cut), ByteArray(0), twice.copyOfRange(cut, twice.size)))
        }
    }
}
