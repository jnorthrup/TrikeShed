package borg.trikeshed.userspace.nio.channels.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import borg.trikeshed.userspace.nio.ByteBuffer

class JvmChannelOperationsConnectTest {

    @Test
    fun `test connect handles closed channel correctly`() {
        // Need to test that when schedule() runs but fd is closed, it doesn`t crash.
        val ops = JvmChannelOperations(entries = 1)
        val fd = ops.socket(0, 0, 0)
        
        val latch = CountDownLatch(1)
        val workerReady = CountDownLatch(1)
        
        // Block the worker thread so connect work gets queued
        ops.schedule {
            workerReady.countDown()
            latch.await(5, TimeUnit.SECONDS)
        }
        workerReady.await(5, TimeUnit.SECONDS)
        
        // This will be queued
        ops.connect(fd, "127.0.0.1", 80)
        
        // Close it before it executes
        ops.close(fd)
        
        // Release worker thread to let connect task run
        latch.countDown()
        
        ops.ioWorkers.shutdown()
        ops.ioWorkers.awaitTermination(5, TimeUnit.SECONDS)
        // Passes if no exception is thrown to console 
    }

    @Test
    fun `test executor saturation rejection deterministic failure`() {
        val ops = JvmChannelOperations(entries = 1)
        val handle = ops.openChannel(1)
        val fd = ops.socket(0, 0, 0)
        
        // Shut down the executor to force rejection
        ops.ioWorkers.shutdown()
        
        val res = ops.connect(fd, "127.0.0.1", 80)
        assertEquals(-1, res, "connect must return -1 on executor rejection")
        
        handle.writev(fd, ByteBuffer(ByteArray(10)))
        val submitted = handle.submit()
        assertEquals(1, submitted, "Should submit 1 op")
        
        val results = handle.wait(1)
        assertEquals(1, results.size, "Should have 1 completed result")
        assertEquals(-1, results[0].res, "Rejection must deterministically surface ChannelResult(-1)")
    }
}
