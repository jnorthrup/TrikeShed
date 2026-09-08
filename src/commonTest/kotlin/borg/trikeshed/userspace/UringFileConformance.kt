package borg.trikeshed.userspace

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared assertions over a real adapter. The platform supplies only a disposable path. */
object UringFileConformance {
    suspend fun file(facade: FunctionalUringFacade, path: String) {
        val fd = one(facade, Submissions.openat(path, flags = 2, userData = 1)).res
        assertTrue(fd >= 0, "OPENAT failed: $fd")
        try {
            // A nonzero array base and position expose adapters which lose the slice offset.
            val bytes = byteArrayOf(99, 98, 97, 11, 12, 13, 14, 96)
            val source = ByteBuffer(bytes).slice(2, 7).position(1)
            assertEquals(2, one(facade, Submissions.write(fd, 0, 2, 0, 2).copy(buffer = source)).res)
            assertEquals(3, source.position(), "only the requested length is consumed")
            assertEquals(1, one(facade, Submissions.write(fd, 0, 1, 2, 3).copy(buffer = source)).res)
            assertEquals(4, source.position())
            assertEquals(0, one(facade, Submissions.fsync(fd, 4)).res)

            val backing = ByteArray(10) { 91 }
            val destination = ByteBuffer(backing).slice(2, 9).position(1).limit(6)
            assertEquals(3, one(facade, Submissions.read(fd, 0, 5, 0, 5).copy(buffer = destination)).res,
                "a short read reports the bytes actually available")
            assertEquals(4, destination.position())
            assertContentEquals(byteArrayOf(91, 91, 91, 11, 12, 13, 91, 91, 91, 91), backing)
            assertEquals(0, one(facade, Submissions.read(fd, 0, 2, 3, 6).copy(buffer = destination)).res,
                "uring EOF is a successful zero-byte completion")
            assertEquals(4, destination.position(), "EOF leaves the position unchanged")

            val readOnly = ByteBuffer(2).asReadOnlyBuffer()
            assertTrue(one(facade, Submissions.read(fd, 0, 2, 0, 7).copy(buffer = readOnly)).res < 0)
            assertEquals(0, readOnly.position())
            val invalid = ByteBuffer(2)
            assertEquals(-22, one(facade, Submissions.read(fd, 0, 3, 0, 70).copy(buffer = invalid)).res)
            assertEquals(-22, one(facade, Submissions.read(fd, 0, 1, -2, 71).copy(buffer = invalid)).res)
            assertEquals(0, invalid.position(), "invalid requests retain the buffer")
            assertEquals(0, one(facade, Submissions.write(fd, 0, 0, 0, 8).copy(buffer = source)).res)
            assertEquals(4, source.position())
            assertTrue(one(facade, UringSubmission(UringOp.READ_MULTISHOT, fd, 0, 1, 0,
                userData = 9, buffer = ByteBuffer(1))).res < 0, "unsupported op must complete with an error")
            assertEquals(0, one(facade, UringSubmission(UringOp.FTRUNCATE, fd, 0, 0, 1, userData = 10)).res)
            val tail = ByteBuffer(2)
            assertEquals(1, one(facade, Submissions.read(fd, 0, 2, 0, 11).copy(buffer = tail)).res)
            assertEquals(11, tail.get(0).toInt())
        } finally {
            assertEquals(0, one(facade, Submissions.close(fd, 12)).res)
        }
        assertTrue(one(facade, Submissions.read(fd, 0, 1, 0, 13).copy(buffer = ByteBuffer(1))).res < 0,
            "a closed descriptor must not reopen itself")
        assertTrue(one(facade, Submissions.close(fd, 14)).res < 0, "descriptor closes exactly once")
    }

    suspend fun one(facade: FunctionalUringFacade, submission: UringSubmission): UringCompletion {
        val results = facade.batchEnqueue(arrayOf(submission).toSeries())
        assertEquals(1, results.size, "one terminal completion per request")
        return results[0].also { assertEquals(submission.userData, it.userData) }
    }
}
