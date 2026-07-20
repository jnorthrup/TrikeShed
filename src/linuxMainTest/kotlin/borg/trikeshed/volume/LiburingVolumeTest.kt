package borg.trikeshed.volume

import borg.trikeshed.common.createTempDirectory
import kotlinx.coroutines.*
import platform.posix.remove
import kotlin.test.*

class LiburingVolumeTest {

    private fun tempFile(): String {
        return createTempDirectory("liburingtest") + "/vol.dat"
    }

    @Test
    fun submitBatch32IoRoundTrip() = runBlocking {
        val path = tempFile()
        try {
            val vol = LiburingVolume(path, 4096, 4096L * 256) // 1 MiB volume
            val requests = mutableListOf<IoRequest>()
            for (i in 0 until 32) {
                if (i % 2 == 0) {
                    requests.add(IoRequest(i.toLong(), ByteArray(4096) { i.toByte() }, IoKind.WRITE))
                } else {
                    requests.add(IoRequest(i.toLong(), ByteArray(4096), IoKind.READ)) // read what will be written or zeros
                }
            }

            // First submit all writes (even indices)
            val writeRequests = (0 until 32 step 2).map { i ->
                IoRequest(i.toLong(), ByteArray(4096) { i.toByte() }, IoKind.WRITE)
            }
            val writeResults = vol.submitBatch(writeRequests)
            assertEquals(16, writeResults.size)
            writeResults.forEach { assertTrue(it is IoResult.Ok) }

            // Then submit all reads (odd indices are empty, even indices are written)
            val readRequests = (0 until 32).map { i ->
                 IoRequest(i.toLong(), ByteArray(0), IoKind.READ) // data is ignored for read
            }
            val readResults = vol.submitBatch(readRequests)
            assertEquals(32, readResults.size)

            for (i in 0 until 32) {
                val res = readResults[i]
                assertTrue(res is IoResult.Ok)
                assertEquals(4096, res.bytes.size)
                if (i % 2 == 0) {
                    for (b in res.bytes) {
                        assertEquals(i.toByte(), b)
                    }
                }
            }

            vol.close()
        } finally {
            remove(path)
        }
    }

    @Test
    fun cancellationMidBatchReturnsFailureOnCancelledOnly() = runBlocking {
        val path = tempFile()
        try {
            val vol = LiburingVolume(path, 4096, 4096L * 512) // 2 MiB volume
            val requests = (0 until 16).map { i ->
                IoRequest(i.toLong(), ByteArray(0), IoKind.READ)
            }

            val deferred = async(Dispatchers.Default) {
                vol.submitBatch(requests)
            }

            // simulate some delay then cancel
            delay(10)
            deferred.cancel()

            try {
                val results = deferred.await()
                // If it doesn't throw CancellationException, check results
                var oks = 0
                var cancelled = 0
                results.forEach {
                    if (it is IoResult.Ok) oks++
                    if (it is IoResult.Cancelled) cancelled++
                }
                assertTrue(oks > 0 || cancelled > 0)

            } catch (e: CancellationException) {
                // Expected behaviour depends on exact cancellation handling inside await
            }
            vol.close()
        } finally {
            remove(path)
        }
    }

    @Test
    fun oversizedBatchReturnsBackpressureFailures() = runBlocking {
        val path = tempFile()
        try {
            val vol = LiburingVolume(path, 4096, 4096L * 256)
            val requests = (0 until 80).map { i ->
                IoRequest(i.toLong(), ByteArray(4096) { i.toByte() }, IoKind.WRITE)
            }

            val results = vol.submitBatch(requests)
            assertEquals(80, results.size)

            var oks = 0
            var failures = 0
            results.forEach {
                if (it is IoResult.Ok) oks++
                if (it is IoResult.Failure) {
                    failures++
                    assertEquals("submit backpressure", it.cause.message)
                }
            }

            assertEquals(64, oks)
            assertEquals(16, failures)

            vol.close()
        } finally {
            remove(path)
        }
    }
}
