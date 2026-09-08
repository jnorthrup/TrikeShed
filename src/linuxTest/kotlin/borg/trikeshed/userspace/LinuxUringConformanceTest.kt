@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.close
import platform.posix.mkstemp
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs the common assertions against the actual selected Linux adapter, when built on Linux. */
class LinuxUringConformanceTest {
    @Test
    fun selected_backend_file_contract() = runTest {
        val path = memScoped {
            val template = "/tmp/trikeshed-uring-XXXXXX".cstr.getPointer(this)
            val fd = mkstemp(template)
            assertTrue(fd >= 0, "disposable file creation failed")
            close(fd)
            template.toKString()
        }
        val backend = openUserspaceChannelBackend(8)
        try {
            println("uring selection: ${backend.availability}; capabilities=${backend.capabilities}; native=${backend.nativeCapabilities}")
            UringFileConformance.file(FunctionalUringFacade(8, backend), path)
        } finally {
            backend.close()
            unlink(path)
        }
    }
}
