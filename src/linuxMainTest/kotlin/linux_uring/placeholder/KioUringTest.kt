package linux_uring.placeholder

import kotlin.test.*
import platform.posix.*
import kotlinx.cinterop.*
import borg.trikeshed.common.createTempDirectory

class KioUringTest {

    @Test
    fun testOpCloseCatFile() = memScoped {
        val path = createTempDirectory("kiouringtest") + "/test_close.txt"
        val fd = open(path, O_CREAT or O_WRONLY, 0.toUInt())
        assertTrue(fd >= 0, "Failed to create temp file")
        close(fd)

        val readFd = open(path, O_RDONLY)
        assertTrue(readFd >= 0, "Failed to open file for read")

        val s = KioUring()

        s.opCloseCatFile(readFd)

        val tail = s.sqRing.tail.pointed.value
        assertEquals(1u, tail, "Tail should have advanced")

        close(readFd)
        remove(path)
    }
}
