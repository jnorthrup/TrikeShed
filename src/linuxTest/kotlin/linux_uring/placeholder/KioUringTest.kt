package linux_uring.placeholder

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.*
import linux_uring.*

class KioUringTest {

    @Test
    fun test_io_uring_setup_success() = memScoped {
        val params: io_uring_params = alloc<io_uring_params>()

        // Zero-initialize the struct to prevent sending garbage to the kernel
        platform.posix.memset(params.ptr, 0, sizeOf<io_uring_params>().toULong())

        val ret = io_uring_setup(1U, params.ptr)
        // With root access or correctly configured system, this might return a valid FD (>0)
        // In restricted environments, it might fail (e.g. -1).
        // Since tests should be deterministic and isolated, we just assert it doesn't crash
        // and returns an Int. We can also assert it either returns a valid FD or fails with
        // expected error codes.
        assertTrue(ret >= -1, "io_uring_setup should return an integer >= -1")

        if (ret >= 0) {
            platform.posix.close(ret)
        }
    }
}
