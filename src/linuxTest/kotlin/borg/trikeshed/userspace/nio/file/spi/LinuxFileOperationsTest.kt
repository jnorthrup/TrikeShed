package borg.trikeshed.userspace.nio.file.spi

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxFileOperationsTest {

    @Test
    fun `readAllBytes fails on non-existent file`() {
        val ops = LinuxFileOperations()
        val exception = assertFailsWith<IllegalArgumentException> {
            ops.readAllBytes("/does/not/exist/foo.txt")
        }
        assertEquals("open(/does/not/exist/foo.txt) failed", exception.message)
    }

    @Test
    fun `write fails on invalid path`() {
        val ops = LinuxFileOperations()
        val exception = assertFailsWith<IllegalArgumentException> {
            ops.write("/does/not/exist/foo.txt", ByteArray(0))
        }
        assertEquals("open(/does/not/exist/foo.txt) failed", exception.message)
    }

    @Test
    fun `write fails on full device`() {
        val ops = LinuxFileOperations()
        val exception = assertFailsWith<IllegalArgumentException> {
            ops.write("/dev/full", ByteArray(10) { 1 })
        }
        assertTrue(exception.message!!.startsWith("short write on /dev/full:"))
    }
}
