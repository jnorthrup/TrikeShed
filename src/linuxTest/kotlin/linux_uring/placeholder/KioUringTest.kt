package linux_uring.placeholder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.posix.*
import kotlinx.cinterop.*

class KioUringTest {
    @Test
    fun testOpReadWholeFile() {
        val s = KioUring()
        val file_fd = open("/dev/null", O_RDONLY)
        assertTrue(file_fd >= 0, "Failed to open /dev/null")

        val initialTail = s.sqRing.tail.pointed.value

        s.opReadWholeFile(file_fd)

        val newTail = s.sqRing.tail.pointed.value
        assertEquals(initialTail + 1u, newTail, "opReadWholeFile should increment sqRing tail")

        close(file_fd)
    }
}
