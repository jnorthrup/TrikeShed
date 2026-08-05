package borg.trikeshed.userspace.nio.file.spi

import kotlin.test.Test
import kotlin.test.assertTrue

class LinuxFileOperationsTest {
    @Test
    fun testListDirErrorPath() {
        val ops = LinuxFileOperations()
        // opendir on a file or non-existent dir should return emptyList()
        val result = ops.listDir("/this/path/does/not/exist/hopefully")
        assertTrue(result.isEmpty())
    }
}
