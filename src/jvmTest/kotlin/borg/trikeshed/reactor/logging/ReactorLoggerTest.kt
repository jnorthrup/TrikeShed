package borg.trikeshed.reactor.logging

import borg.trikeshed.couch.isam.FileBackedStringpool
import borg.trikeshed.job.JobLog
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactorLoggerTest {

    @Test
    fun testLogCasFormatting() {
        val stringpool = FileBackedStringpool("test", InMemoryFileOperations())
        val wal = JobLog.inMemory()

        val logger = ReactorLogger("test-logger", stringpool, wal)

        logger.info("User {} logged in from IP {}", "john.doe", "192.168.1.1")

        // Retrieve the WAL frames
        val map = wal.toMap()
        assertEquals(1, map.size)
        val payload = map.values.first()

        // Parse payload back to verify assertions
        // [level:1][templateCas:4][timestamp:8][args count:4][args...]
        assertTrue(payload.size > 1 + 4 + 8 + 4)

        val level = payload[0]
        assertEquals(1.toByte(), level) // Info level

        var templateCas = 0
        for (i in 0..3) {
            templateCas = (templateCas shl 8) or (payload[1 + i].toInt() and 0xFF)
        }

        // Assert that the template was stored in Stringpool
        val templateStr = stringpool.get(templateCas)
        assertEquals("User {} logged in from IP {}", templateStr)

        var timestamp = 0L
        for (i in 0..7) {
            timestamp = (timestamp shl 8) or (payload[5 + i].toLong() and 0xFFL)
        }

        // Assert Epoch time UTC (Should be recently created)
        val now = System.currentTimeMillis()
        assertTrue(timestamp > 0)
        assertTrue(timestamp <= now)
        assertTrue(now - timestamp < 1000) // Less than a second old

        var argCount = 0
        for (i in 0..3) {
            argCount = (argCount shl 8) or (payload[13 + i].toInt() and 0xFF)
        }
        assertEquals(2, argCount)
    }
}
