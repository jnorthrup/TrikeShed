package linux_uring.placeholder

import kotlinx.cinterop.*
import linux_uring.include.*
import zlinux_uring.*
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
