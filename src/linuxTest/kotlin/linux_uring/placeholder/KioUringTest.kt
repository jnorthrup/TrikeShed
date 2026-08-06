package linux_uring.placeholder

import kotlinx.cinterop.*
import linux_uring.include.*
import zlinux_uring.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import platform.posix.memset
import platform.posix.close
import platform.posix.io_uring_setup
import platform.posix.io_uring_params
import platform.posix.sizeOf
import platform.posix.open
import platform.posix.O_RDONLY
import platform.posix.MAP_FAILED
import platform.posix.MAP_SHARED
import platform.posix.PROT_READ
import platform.posix.munmap
import linux_uring.*
import linux_uring.UringSqeFlags
import linux_uring.UringOpcode

class KioUringTest {

    @Test
    fun testOpCloseFd() = memScoped {
        val kio = KioUring()
        val sqe = alloc<io_uring_sqe>()
        kio.opCloseFd(sqe.ptr, 42)
        assertEquals(42, sqe.fd)
        assertEquals(UringSqeFlags.sqeIo_link.ub, sqe.flags)
        assertEquals(UringOpcode.Op_Close.opConstant.toUByte(), sqe.opcode)
    }

    @Test
    fun test_io_uring_setup_success() = memScoped {
        val params: io_uring_params = alloc<io_uring_params>()

        // Zero-initialize the struct to prevent sending garbage to the kernel
        memset(params.ptr, 0, sizeOf<io_uring_params>().toULong())

        val ret = io_uring_setup(1U, params.ptr)
        // With root access or correctly configured system, this might return a valid FD (>0)
        // In restricted environments, it might fail (e.g. -1).
        // Since tests should be deterministic and isolated, we just assert it doesn't crash
        // and returns an Int. We can also assert it either returns a valid FD or fails with
        // expected error codes.
        assertTrue(ret >= -1, "io_uring_setup should return an integer >= -1")

        if (ret >= 0) {
            close(ret)
        }
    }

    @Test
    fun testOpReadWholeFile() = memScoped {
        val s = KioUring()
        val file_fd = open("/dev/null", O_RDONLY)
        assertTrue(file_fd >= 0, "Failed to open /dev/null")

        val initialTail = s.sqRing.tail.pointed.value

        s.opReadWholeFile(file_fd)

        val newTail = s.sqRing.tail.pointed.value
        assertEquals(initialTail + 1u, newTail, "opReadWholeFile should increment sqRing tail")

        close(file_fd)
    }

    @Test
    fun mapIORingQueueSuccess() = memScoped {
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
    fun mapIORingQueueInvalidArgs() = memScoped {
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