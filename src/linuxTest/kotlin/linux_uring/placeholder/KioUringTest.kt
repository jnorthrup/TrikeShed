package linux_uring.placeholder

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import platform.posix.MAP_FAILED
import platform.posix.MAP_SHARED
import platform.posix.PROT_READ
import platform.posix.close
import platform.posix.munmap
import zlinux_uring.*

class KioUringTest {

    @Test
    fun mapIORingQueueSuccess() {
        val uring = KioUring()
        val len = 4096uL
        val ptr = uring.mapIORingQueue(
            __len = len,
            __prot = PROT_READ,
            __flags = MAP_SHARED,
            __offset = IORING_OFF_SQ_RING.toLong()
        )
        assertTrue(ptr != MAP_FAILED)
        munmap(ptr, len)
        close(uring.ring_fd)
    }

    @Test
    fun mapIORingQueueInvalidArgs() {
        val uring = KioUring()
        val exception = assertFailsWith<IllegalArgumentException> {
            uring.mapIORingQueue(
                __len = ULong.MAX_VALUE,
                __prot = PROT_READ,
                __flags = MAP_SHARED,
                __offset = 0L
            )
        }
        assertTrue(exception.message!!.contains("mmap"))
        close(uring.ring_fd)
    }
}
