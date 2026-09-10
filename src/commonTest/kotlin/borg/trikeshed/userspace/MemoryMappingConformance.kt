package borg.trikeshed.userspace

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Platform tests provide actual mmap and real file descriptors to these shared assertions. */
object MemoryMappingConformance {
    fun anonymous(length: Long) {
        val mapping = mapMemory(0, length, 3, 2 or 0x20, -1, 0)
        try {
            assertTrue(mapping.address > 0)
            assertEquals(0.toByte(), mapping[0])
            val bytes = byteArrayOf(11, 22, 33, 44)
            mapping.write(7, bytes)
            val read = ByteArray(6)
            mapping.read(7, read, 1, bytes.size)
            assertContentEquals(byteArrayOf(0, 11, 22, 33, 44, 0), read)
            assertEquals(0, adviseMemory(mapping.address, mapping.length, 3))
            assertFails { mapping[-1] }
            assertFails { mapping[length] }
            assertFails { mapping.read(length - 1, ByteArray(2)) }
            assertFails { mapping.write(0, bytes, Int.MAX_VALUE, 1) }
            mapping.retain()
            assertFails { mapping.close() }
            assertTrue(mapping.isOpen)
            mapping.release()
        } finally { mapping.close() }
        assertFalse(mapping.isOpen)
        assertFails { mapping[0] }
        assertFails { mapping.retain() }
        mapping.close()
    }

    fun file(fd: Int, length: Long, closeDescriptor: () -> Unit, readFile: () -> ByteArray) {
        val shared = mapMemory(0, length, 3, 1, fd, 0)
        try {
            val privateMapping = mapMemory(0, length, 3, 2, fd, 0)
            try {
                val readOnly = mapMemory(0, length, 1, 1, fd, 0)
                try {
                    closeDescriptor()
                    shared[0] = 41
                    shared[1] = 42
                    privateMapping[2] = 99
                    shared.sync()
                    privateMapping.sync()
                    assertEquals(41.toByte(), readOnly[0])
                    assertEquals(42.toByte(), readOnly[1])
                    assertEquals(99.toByte(), privateMapping[2])
                    assertEquals(17.toByte(), shared[2])
                    assertFails { readOnly[0] = 7 }
                    assertFails { shared.sync(1, 1) }
                    val persisted = readFile()
                    assertEquals(41.toByte(), persisted[0])
                    assertEquals(42.toByte(), persisted[1])
                    assertEquals(17.toByte(), persisted[2], "MAP_PRIVATE changes must not write back")
                } finally { readOnly.close() }
            } finally { privateMapping.close() }
        } finally { shared.close() }
    }
}
