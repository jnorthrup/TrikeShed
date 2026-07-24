package borg.trikeshed.sctp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedChannelStreamTest {

    @Test
    fun testEnqueueDequeue() = runTest {
        val stream = BoundedChannelStream(capacity = 2)
        assertTrue(stream.enqueue("chunk1".encodeToByteArray()))
        assertTrue(stream.enqueue("chunk2".encodeToByteArray()))

        val overflow = !stream.enqueue("chunk3".encodeToByteArray())
        assertTrue(overflow, "Should overflow")

        val chunk1 = stream.dequeue()
        assertEquals("chunk1", chunk1?.decodeToString())

        val chunk2 = stream.dequeue()
        assertEquals("chunk2", chunk2?.decodeToString())
    }
}
