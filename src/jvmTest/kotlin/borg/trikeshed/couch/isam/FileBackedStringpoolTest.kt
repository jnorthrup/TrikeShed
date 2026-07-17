package borg.trikeshed.couch.isam

import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileBackedStringpoolTest {

    @Test
    fun testPutAndGet() {
        val fileOps = InMemoryFileOperations()
        val pool = FileBackedStringpool("/mem/pool.log", fileOps)

        val offset1 = pool.put("Hello, World!")
        val offset2 = pool.put("Another string")

        assertEquals("Hello, World!", pool.get(offset1))
        assertEquals("Another string", pool.get(offset2))
    }

    @Test
    fun testDuplicateMemoization() {
        val fileOps = InMemoryFileOperations()
        val pool = FileBackedStringpool("/mem/pool.log", fileOps)

        val offset1 = pool.put("Hello, World!")
        val offset2 = pool.put("Hello, World!")

        assertEquals(offset1, offset2)
    }

    @Test
    fun testRestartRecovery() {
        val fileOps = InMemoryFileOperations()

        val pool1 = FileBackedStringpool("/mem/pool.log", fileOps)
        val offset1 = pool1.put("Hello, World!")
        val offset2 = pool1.put("Another string")

        // Create a new instance pointing to the same file
        val pool2 = FileBackedStringpool("/mem/pool.log", fileOps)

        // Memoized on startup, so duplicate insert should return same offset
        val offset3 = pool2.put("Hello, World!")
        assertEquals(offset1, offset3)

        assertEquals("Hello, World!", pool2.get(offset1))
        assertEquals("Another string", pool2.get(offset2))

        // Append a new one
        val offset4 = pool2.put("Third string")
        assertEquals("Third string", pool2.get(offset4))
    }

    @Test
    fun testCorruptTailBehavior() {
        val fileOps = InMemoryFileOperations()

        val pool1 = FileBackedStringpool("/mem/pool.log", fileOps)
        val offset1 = pool1.put("Hello, World!")

        // Corrupt the file by truncating the last frame
        val bytes = fileOps.readAllBytes("/mem/pool.log")
        fileOps.write("/mem/pool.log", bytes.sliceArray(0 until bytes.size - 5))

        val pool2 = FileBackedStringpool("/mem/pool.log", fileOps)
        // Corrupted tail should be truncated on restart, but we lost the first record because there's only 1 record
        assertNull(pool2.get(offset1))

        // Re-inserting should work at offset 0
        val offset2 = pool2.put("Recovered string")
        assertEquals(0, offset2)
    }
}
