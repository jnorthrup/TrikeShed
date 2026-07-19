package borg.trikeshed.forge.persistence

import kotlin.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CausalWalTest {

    @Test
    fun testWalAppendAndReplay() = kotlinx.coroutines.runBlocking {
        val tempFile = Files.createTempFile("causal-wal-test", ".wal").toFile()
        tempFile.deleteOnExit()

        // 1. Write three records
        val wal1 = CausalWal(tempFile)
        val r1 = "key1" to "payload1".encodeToByteArray()
        val r2 = "key2" to "payload2".encodeToByteArray()
        val r3 = "key3" to "payload3".encodeToByteArray()

        val off1 = wal1.append(r1.first, r1.second)
        val off2 = wal1.append(r2.first, r2.second)
        val off3 = wal1.append(r3.first, r3.second)

        assertTrue(off1 > 0)
        assertTrue(off2 > off1)
        assertTrue(off3 > off2)

        // 2. Close it
        wal1.close()

        // 3. Construct a fresh CausalWal on the same path
        val wal2 = CausalWal(tempFile)

        // 4. Replay and assert all three causalKeys come back in order
        val records = wal2.replay().toList()

        assertEquals(3, records.size)

        assertEquals("key1", records[0].first)
        assertArrayEquals("payload1".encodeToByteArray(), records[0].second)

        assertEquals("key2", records[1].first)
        assertArrayEquals("payload2".encodeToByteArray(), records[1].second)

        assertEquals("key3", records[2].first)
        assertArrayEquals("payload3".encodeToByteArray(), records[2].second)

        wal2.close()
    }
}
