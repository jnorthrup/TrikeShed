@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryMappingTest {
    @Test fun anonymous_memory_has_owned_lifetime_and_real_byte_access() =
        MemoryMappingConformance.anonymous(65536)

    @Test fun shared_and_private_mappings_outlive_their_descriptor() = memScoped {
        val template = "/tmp/trikeshed-mmap-XXXXXX".cstr.getPointer(this)
        var fd = mkstemp(template)
        assertTrue(fd >= 0)
        val path = template.toKString()
        try {
            val initial = ByteArray(65536) { 17 }
            initial.usePinned { assertEquals(initial.size.toLong(), pwrite(fd, it.addressOf(0), initial.size.convert(), 0).toLong()) }
            MemoryMappingConformance.file(fd, initial.size.toLong(), {
                assertEquals(0, close(fd))
                fd = -1
            }, {
                val reader = open(path, O_RDONLY)
                assertTrue(reader >= 0)
                try {
                    val bytes = ByteArray(initial.size)
                    bytes.usePinned { assertEquals(bytes.size.toLong(), pread(reader, it.addressOf(0), bytes.size.convert(), 0).toLong()) }
                    bytes
                } finally { close(reader) }
            })
        } finally {
            if (fd >= 0) close(fd)
            unlink(path)
        }
    }
}
