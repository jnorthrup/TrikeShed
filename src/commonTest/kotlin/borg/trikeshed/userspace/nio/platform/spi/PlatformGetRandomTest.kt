package borg.trikeshed.userspace.nio.platform.spi

import kotlin.test.*

class PlatformGetRandomTest {
    /** getentropy(2) fills 256 bytes and getRandomValues 65536 per call: every 256-byte block of one fill is written. */
    @Test fun fillsEveryBlock() {
        val bytes = ByteArray(70_000)
        platformGetRandom(bytes)
        for (block in bytes.indices step 256) {
            assertTrue((block until minOf(block + 256, bytes.size)).any { bytes[it].toInt() != 0 }, "block at $block")
        }
        assertFalse(ByteArray(32).also(::platformGetRandom).contentEquals(ByteArray(32).also(::platformGetRandom)))
    }
}
