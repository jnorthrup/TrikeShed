/*
 * Copyright (c) 2024-2026. The TrikeShed Authors.
 * Licensed under the AGPLv3.
 */
package borg.trikeshed.reactor.ngsctp

import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class SctpReactorSpineTest {

    @Test
    fun testTlvChunkParserUnknownSkipBehavior() {
        val parser = TlvChunkParser()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x08, 0x01, 0x02, 0x03, 0x04, // Chunk Type 0 (DATA), length 8
            0x80.toByte(), 0x00, 0x00, 0x08, 0x09, 0x0A, 0x0B, 0x0C, // Chunk Type 0x80 (Unknown, skip)
            0xC0.toByte(), 0x00, 0x00, 0x08, 0x09, 0x0A, 0x0B, 0x0C, // Chunk Type 0xC0 (Unknown, skip)
            0x00, 0x00, 0x00, 0x08, 0x0D, 0x0E, 0x0F, 0x10  // Chunk Type 0 (DATA), length 8
        )
        val chunks = parser.parse(data)
        assertEquals(2, chunks.size, "Expected 2 DATA chunks")
        assertEquals(0x00, chunks[0].type)
        assertEquals(0x00, chunks[1].type)
    }

    @Test
    fun testBoundedChannelStreamEnqueueDequeue() = runTest {
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

    @Test
    fun testAssociationScopeCancellation() = runTest {
        val assoc = SctpAssociationScope()
        var jobStarted = false
        var jobCancelled = false
        val job = assoc.launch {
            try {
                jobStarted = true
                delay(10000)
            } finally {
                jobCancelled = true
            }
        }
        while (!jobStarted) {
            delay(10)
        }
        assoc.close()
        job.join()
        assertTrue(jobCancelled)
    }

    @Test
    fun testLiburingFacadeInterface() {
        val facade: LiburingFacade = DummyLiburingFacade()
        assertNotNull(facade)

        val batch: borg.trikeshed.lib.Series<ByteArray> = 1 j { byteArrayOf(1) }
        facade.submitBatch(batch)
        val completed = facade.completeBatch()
        assertEquals(1, completed)
    }

    @Test
    fun testSctpReactorSpineLifecycle() = runTest {
        val spine = SctpReactorSpine()
        assertEquals(8080, spine.bind(8080))
        spine.close()
    }
}

class DummyLiburingFacade : LiburingFacade {
    var submitted = 0
    override fun submitBatch(batch: borg.trikeshed.lib.Series<ByteArray>) {
        submitted += batch.size
    }

    override fun completeBatch(): Int {
        val res = submitted
        submitted = 0
        return res
    }
}
