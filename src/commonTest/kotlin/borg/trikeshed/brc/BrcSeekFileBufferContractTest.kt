package borg.trikeshed.brc

import borg.trikeshed.Files
import borg.trikeshed.SeekFileBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for SeekFileBuffer across platforms.
 * Uses the default-constructor pattern that works on all platforms
 * after adding default parameter values to POSIX + JS + WASM actuals.
 */
class BrcSeekFileBufferContractTest {

    @Test
    fun `open read-only and read byte at offset`() {
        val dir = Files.createTempDir("brc")
        try {
            val file = "$dir/data.bin"
            Files.write(file, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))

            val buf = SeekFileBuffer(file)
            buf.open()
            assertTrue(buf.isOpen())
            assertEquals(5L, buf.size())

            assertEquals(0x01.toByte(), buf.get(0L))
            assertEquals(0x03.toByte(), buf.get(2L))
            assertEquals(0x05.toByte(), buf.get(4L))

            buf.close()
        } finally {
            Files.deleteRecursively(dir)
        }
    }

    @Test
    fun `read with offset`() {
        val dir = Files.createTempDir("brc")
        try {
            val file = "$dir/offset.bin"
            Files.write(file, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06))

            val buf = SeekFileBuffer(file, initialOffset = 3L)
            buf.open()
            assertTrue(buf.isOpen())
            assertEquals(3L, buf.size())
            assertEquals(0x04.toByte(), buf.get(0L))
            assertEquals(0x06.toByte(), buf.get(2L))

            buf.close()
        } finally {
            Files.deleteRecursively(dir)
        }
    }

    @Test
    fun `read-only by default`() {
        val dir = Files.createTempDir("brc")
        try {
            val file = "$dir/readonly.bin"
            Files.write(file, byteArrayOf(0x0A, 0x0B))

            val buf = SeekFileBuffer(file)  // readOnly = true by default
            buf.open()
            assertEquals(2L, buf.size())
            buf.close()
        } finally {
            Files.deleteRecursively(dir)
        }
    }
}
