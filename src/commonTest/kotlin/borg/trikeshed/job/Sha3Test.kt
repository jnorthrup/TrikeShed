package borg.trikeshed.job

import borg.trikeshed.util.toLowerHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** FIPS 202 known answers for SHA3-224/256/384/512, across the rate boundary. */
class Sha3Test {
    val sha3_256 = listOf(
        "".encodeToByteArray() to "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
        "abc".encodeToByteArray() to "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray() to
            "41c0dba2a9d6240849100376a8235e2c82e1b9998a999e21db32dd97496d3376",
        ByteArray(135) { 0xA3.toByte() } to "d51927265ca4bf0cc8b4453387700918c03f8894e395ad437d4573f3be4d2c34",
        ByteArray(136) { 0xA3.toByte() } to "0adf6bfb359ae40019b67d8c49c361574b70242a6b752de6f9e0d426ca177f7a",
        ByteArray(200) { 0xA3.toByte() } to "79f38adec5c20307a98ef76e8324afbfd46cfd81b22e3973c65fa1bd9de31787",
    )

    @Test fun sha3_256KnownAnswers() {
        for ((message, hex) in sha3_256) assertEquals(hex, Sha3Pure.digest(message).toLowerHex(), "${message.size} bytes")
    }

    @Test fun sha3_256InPieces() {
        val million = Sha3(32)
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { million.update(chunk, it % 7, 1000 - it % 7); million.update(chunk, 0, it % 7) }
        assertEquals("5c8875ae474a3634ba4fd55ec85bffd661f32aca75c6d699d0cdcb6c115891c1", million.digest().toLowerHex())
    }

    @Test fun otherSizesOfEmpty() {
        assertEquals("6b4e03423667dbb73b6e15454f0eb1abd4597f9a1b078e3f5b5a6bc7", Sha3(28).digest().toLowerHex())
        assertEquals("0c63a75b845e4f7d01107d852e4c2485c51a50aaaa94fc61995e71bbee983a2ac3713831264adb47fb6bd1e058d5f004",
            Sha3(48).digest().toLowerHex())
        assertEquals("a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a615b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26",
            Sha3(64).digest().toLowerHex())
    }
}
