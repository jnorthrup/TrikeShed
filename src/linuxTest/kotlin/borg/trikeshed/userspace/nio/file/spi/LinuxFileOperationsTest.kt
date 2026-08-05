package borg.trikeshed.userspace.nio.file.spi

import kotlin.test.Test
import kotlin.test.assertFailsWith

class LinuxFileOperationsTest {
    @Test
    fun testReadAllBytesFileNotFound() {
        val ops = LinuxFileOperations()
        assertFailsWith<IllegalArgumentException> {
            ops.readAllBytes("/does/not/exist/very/unlikely/to/exist.txt")
        }
    }
}
