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
import kotlinx.coroutines.CancellationException
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
    fun testAssociationScopeCancellation() = runTest {
        val assoc = SctpAssociationScope()
        var jobCancelled = false
        val job = assoc.launch {
            try {
                delay(1000)
            } catch (e: CancellationException) {
                jobCancelled = true
            } finally {
                jobCancelled = true
            }
        }
        assoc.close()
        job.join()
        assertTrue(jobCancelled)
    }

    @Test
    fun testPartialReliabilityDropsOldestUnacked() {
        val prBuffer = PartialReliabilityBuffer(capacity = 2)
        prBuffer.enqueue(1, "chunk1".encodeToByteArray())
        prBuffer.enqueue(2, "chunk2".encodeToByteArray())

        // At capacity, adding 3 should drop oldest (1)
        prBuffer.enqueue(3, "chunk3".encodeToByteArray())

        val chunks = prBuffer.getAllUnacked()
        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].first)
        assertEquals(3, chunks[1].first)
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

    @Test
    fun testSubnetJobBufferCapacityTrigger() = runTest {
        var triggerCount = 0
        var lastBatchSize = 0
        
        val buffer = SubnetJobBuffer(
            subnet = "test.subnet",
            capacity = 3,
            onFull = { batch ->
                triggerCount++
                lastBatchSize = batch.size
            }
        )
        
        // Enqueue 2 jobs - should not trigger
        assertTrue(!buffer.enqueue("job1".encodeToByteArray()))
        assertTrue(!buffer.enqueue("job2".encodeToByteArray()))
        assertEquals(0, triggerCount)
        assertEquals(2, buffer.size())
        
        // Enqueue 3rd job - should trigger (capacity = 3)
        assertTrue(buffer.enqueue("job3".encodeToByteArray()))
        assertEquals(1, triggerCount)
        assertEquals(3, lastBatchSize)
        
        // Buffer should be cleared after trigger
        assertEquals(0, buffer.size())
        
        // Enqueue 2 more - should not trigger
        assertTrue(!buffer.enqueue("job4".encodeToByteArray()))
        assertTrue(!buffer.enqueue("job5".encodeToByteArray()))
        assertEquals(1, triggerCount)
        
        // Enqueue 3rd - should trigger again
        assertTrue(buffer.enqueue("job6".encodeToByteArray()))
        assertEquals(2, triggerCount)
        assertEquals(3, lastBatchSize)
    }

    @Test
    fun testSubnetJobAssemblyDefaultCapacities() {
        val assembly = SubnetJobAssembly()
        
        // Verify default concentric capacities
        assertEquals(3, SubnetJobAssembly.capacityForSubnet("core"))
        assertEquals(5, SubnetJobAssembly.capacityForSubnet("process.self"))
        assertEquals(10, SubnetJobAssembly.capacityForSubnet("local"))
        assertEquals(20, SubnetJobAssembly.capacityForSubnet("lan.localhost"))
        
        // Unknown subnet defaults to local
        assertEquals(10, SubnetJobAssembly.capacityForSubnet("unknown.subnet"))
    }

    @Test
    fun testSubnetJobAssemblyCustomCapacities() = runTest {
        val customCaps = mapOf(
            "core" to 2,
            "mesh.worker.1" to 15
        )
        
        val assembly = SubnetJobAssembly(
            fanout = null,
            defaultCapacity = 5,
            subnetCapacities = customCaps
        )
        
        // Register subnets with custom capacities
        assembly.registerSubnet("core", 2)
        assembly.registerSubnet("mesh.worker.1", 15)
        
        // Enqueue to core - should trigger at 2
        assertTrue(!assembly.enqueue("core", "job1".encodeToByteArray()))
        assertTrue(assembly.enqueue("core", "job2".encodeToByteArray())) // triggers
        
        // Enqueue to mesh.worker.1 - should trigger at 15
        for (i in 1..14) {
            assertTrue(!assembly.enqueue("mesh.worker.1", "job$i".encodeToByteArray()))
        }
        assertTrue(assembly.enqueue("mesh.worker.1", "job15".encodeToByteArray())) // triggers
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
