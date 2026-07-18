package borg.trikeshed.reactor.logging

import borg.trikeshed.couch.isam.FileBackedStringpool
import borg.trikeshed.job.JobLog
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.couch.isam.DurableAppendLog
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

        // Assert sequence ordering
        logger.info("Next log")
        val map2 = wal.toMap()
        assertEquals(2, map2.size)
        val keys = map2.keys.toList().sortedBy { it.toLong() }
        assertTrue(keys[1].toLong() > keys[0].toLong())
    }

    @Test
    fun testDurabilityFlush() {
        val stringpool = FileBackedStringpool("test", InMemoryFileOperations())
        val wal = JobLog.inMemory()

        var flushed = false
        var appended = false
        val mockDurableLog = object : DurableAppendLog {
            override fun append(sequence: Long, payload: ByteArray): Long {
                appended = true
                return sequence
            }
            override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long = 0L
            override fun flush() { flushed = true }
            override fun injectCorruptionAfter(sequence: Long) {}
        }

        val logger = ReactorLogger("test-logger", stringpool, wal, mockDurableLog)

        logger.info("Testing durability")

        assertTrue(appended)
        assertTrue(flushed)
    }

    @Test
    fun testDecodingSupportRoundTrip() {
        val stringpool = FileBackedStringpool("test", InMemoryFileOperations())
        val wal = JobLog.inMemory()

        val logger = ReactorLogger("test-logger", stringpool, wal)

        val arg1 = "test arg"
        val arg2 = 42
        val template = "Test template with arg1={} and arg2={}"
        logger.info(template, arg1, arg2)

        val map = wal.toMap()
        val payload = map.values.first()

        val level = payload[0]
        assertEquals(1.toByte(), level)

        var offset = 1
        var templateCas = 0
        for (i in 0..3) {
            templateCas = (templateCas shl 8) or (payload[offset + i].toInt() and 0xFF)
        }
        offset += 4

        assertEquals(template, stringpool.get(templateCas))

        var timestamp = 0L
        for (i in 0..7) {
            timestamp = (timestamp shl 8) or (payload[offset + i].toLong() and 0xFFL)
        }
        offset += 8

        var argCount = 0
        for (i in 0..3) {
            argCount = (argCount shl 8) or (payload[offset + i].toInt() and 0xFF)
        }
        offset += 4

        assertEquals(2, argCount)

        // Read args
        var len1 = 0
        for (i in 0..3) {
            len1 = (len1 shl 8) or (payload[offset + i].toInt() and 0xFF)
        }
        offset += 4
        val arg1Str = payload.sliceArray(offset until offset + len1).decodeToString()
        assertEquals("test arg", arg1Str)
        offset += len1

        var len2 = 0
        for (i in 0..3) {
            len2 = (len2 shl 8) or (payload[offset + i].toInt() and 0xFF)
        }
        offset += 4
        val arg2Str = payload.sliceArray(offset until offset + len2).decodeToString()
        assertEquals("42", arg2Str)
    }
}
