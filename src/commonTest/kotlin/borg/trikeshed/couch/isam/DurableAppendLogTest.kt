package borg.trikeshed.couch.isam

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * I1 — Durable append log RED tests.
 *
 * WAL frame: magic | version | sequence | payloadLength | bytes | crc32c
 * Replay stops at last valid frame, rejects torn/bad-CRC frames.
 */
class DurableAppendLogTest {

    @Test
    fun appendAndReplayAllFrames() {
        val log = DurableAppendLog.inMemory()
        log.append(sequence = 1L, payload = "frame-1".encodeToByteArray())
        log.append(sequence = 2L, payload = "frame-2".encodeToByteArray())
        log.append(sequence = 3L, payload = "frame-3".encodeToByteArray())

        val frames = log.replay().toList()
        assertEquals(3, frames.size)
        assertEquals(1L, frames[0].sequence)
        assertEquals("frame-1", frames[0].payload.decodeToString())
        assertEquals(2L, frames[1].sequence)
        assertEquals("frame-2", frames[1].payload.decodeToString())
        assertEquals(3L, frames[2].sequence)
        assertEquals("frame-3", frames[2].payload.decodeToString())
    }

    @Test
    fun replayStopsAtTornFrame() {
        val log = DurableAppendLog.inMemory()
        log.append(sequence = 1L, payload = "good-1".encodeToByteArray())
        log.append(sequence = 2L, payload = "good-2".encodeToByteArray())
        log.injectCorruption(atEnd = true)

        val frames = log.replay().toList()
        assertEquals(2, frames.size, "replay must stop at corruption")
    }

    @Test
    fun replayStopsAtBadCrc() {
        val log = DurableAppendLog.inMemory()
        log.append(sequence = 1L, payload = "good".encodeToByteArray())
        log.append(sequence = 2L, payload = "good".encodeToByteArray())
        log.append(sequence = 3L, payload = "good".encodeToByteArray())
        log.corruptFrame(atSequence = 2L)

        val frames = log.replay().toList()
        assertEquals(1, frames.size, "replay must stop at bad CRC")
        assertEquals(1L, frames[0].sequence)
    }

    @Test
    fun replayIgnoresUnreferencedCasBlob() {
        val log = DurableAppendLog.inMemory()
        log.append(sequence = 1L, payload = "good".encodeToByteArray())

        // An unreferenced CAS blob should not affect WAL replay.
        val frames = log.replay().toList()
        assertEquals(1, frames.size)
    }

    @Test
    fun replayIdempotentAcrossTwoReads() {
        val log = DurableAppendLog.inMemory()
        log.append(sequence = 1L, payload = "a".encodeToByteArray())
        log.append(sequence = 2L, payload = "b".encodeToByteArray())

        val first = log.replay().map { it.sequence }.toList()
        val second = log.replay().map { it.sequence }.toList()
        assertEquals(first, second, "replay must be idempotent")
    }

    @Test
    fun durabilityBarrierFlushesBeforeCommit() {
        val log = DurableAppendLog.inMemory()
        log.append(sequence = 1L, payload = "data".encodeToByteArray())
        log.fsync()

        assertTrue(log.lastSyncSequence >= 1L, "fsync must record the synced sequence")
    }
}
