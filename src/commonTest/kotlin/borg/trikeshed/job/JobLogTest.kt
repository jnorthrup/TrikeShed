package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * I1 — JobLog (WAL) contract tests.
 *
 * Spec (§I1 / §C04 durable-before-visible):
 *  - append is monotonic by sequence
 *  - replay returns every appended frame in order
 *  - a torn frame (empty payload injected via injectTornFrame) stops replay
 *  - replay is idempotent: calling it twice yields the same frames
 *  - toMap / fromMap round-trip preserves frames and order
 *  - Frame equality is by sequence + payload content, not reference
 */
class JobLogTest {

    @Test
    fun appendThenReplayReturnsFramesInOrder() {
        val log = JobLog.inMemory()
        log.append(1L, "one".encodeToByteArray())
        log.append(2L, "two".encodeToByteArray())
        log.append(3L, "three".encodeToByteArray())

        val frames = log.replay().toList()
        assertEquals(3, frames.size)
        assertEquals(1L, frames[0].sequence)
        assertEquals(2L, frames[1].sequence)
        assertEquals(3L, frames[2].sequence)
        assertTrue(frames[0].payload.contentEquals("one".encodeToByteArray()))
        assertTrue(frames[2].payload.contentEquals("three".encodeToByteArray()))
    }

    @Test
    fun replayStopsAtTornFrame() {
        val log = JobLog.inMemory()
        log.append(1L, "good".encodeToByteArray())
        log.append(2L, "good".encodeToByteArray())
        log.injectTornFrame(3L, "lost".encodeToByteArray()) // payload written empty
        log.append(4L, "after-torn".encodeToByteArray())

        val frames = log.replay().toList()
        // Torn frame produces an empty payload, terminating replay at index 2.
        assertEquals(2, frames.size, "replay must stop at the torn frame")
        assertEquals(2L, frames.last().sequence, "last replayed frame is sequence 2")
    }

    @Test
    fun replayIsIdempotent() {
        val log = JobLog.inMemory()
        log.append(1L, "a".encodeToByteArray())
        log.append(2L, "b".encodeToByteArray())

        val first = log.replay().toList()
        val second = log.replay().toList()
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i].sequence, second[i].sequence)
            assertTrue(first[i].payload.contentEquals(second[i].payload))
        }
    }

    @Test
    fun toMapFromMapRoundTrips() {
        val original = JobLog.inMemory()
        original.append(1L, "alpha".encodeToByteArray())
        original.append(2L, "beta".encodeToByteArray())
        val map = original.toMap()

        val restored = JobLog.fromMap(map)
        val orig = original.replay().toList()
        val rest = restored.replay().toList()
        assertEquals(orig.size, rest.size)
        for (i in orig.indices) {
            assertEquals(orig[i].sequence, rest[i].sequence)
            assertTrue(orig[i].payload.contentEquals(rest[i].payload))
        }
    }

    @Test
    fun fromMapOrdersByNumericSequence() {
        val map = mutableMapOf<String, ByteArray>()
        // Insert out of string-lexical order; fromMap must sort numerically.
        map["10"] = "ten".encodeToByteArray()
        map["2"]  = "two".encodeToByteArray()
        map["1"]  = "one".encodeToByteArray()
        val log = JobLog.fromMap(map)
        val frames = log.replay().toList()
        assertEquals(listOf(1L, 2L, 10L), frames.map { it.sequence },
            "fromMap must sort keys by numeric value, not lexical string order")
    }

    @Test
    fun frameEqualityIsByContentNotReference() {
        val a = JobLog.Frame(7L, "payload".encodeToByteArray())
        val b = JobLog.Frame(7L, "payload".encodeToByteArray())
        assertEquals(a, b, "frames with same sequence and payload content are equal")
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, JobLog.Frame(7L, "different".encodeToByteArray()))
        assertNotEquals(a, JobLog.Frame(8L, "payload".encodeToByteArray()))
    }

    @Test
    fun emptyLogReplaysToNothing() {
        val log = JobLog.inMemory()
        assertEquals(0, log.replay().toList().size)
        assertTrue(log.toMap().isEmpty())
    }

    @Test
    fun appendCopiesPayloadDefensively() {
        val log = JobLog.inMemory()
        val input = "mutable".encodeToByteArray()
        log.append(1L, input)
        input[0] = 0x00 // mutate after append
        val replayed = log.replay().toList()
        assertTrue(replayed[0].payload.contentEquals("mutable".encodeToByteArray()),
            "append must defensively copy so caller mutation cannot corrupt the log")
    }
}