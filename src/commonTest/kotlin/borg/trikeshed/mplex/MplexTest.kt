package borg.trikeshed.mplex

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MplexTest {
    @Test
    fun testFlowWindow() = runTest {
        val window = FlowWindow(10)
        assertEquals(10, window.available)

        window.consume(5)
        assertEquals(5, window.available)

        // Waiters requesting more than what's available
        val job1 = async { window.consume(10); 1 }

        // Test race condition protection: second waiter also requesting more than available initially
        val job2 = async { window.consume(5); 2 }

        delay(50)
        assertEquals(false, job1.isCompleted)
        assertEquals(true, job2.isCompleted) // Job 2 requires 5, 5 is available, so it completes!

        // Now 0 is available
        window.update(10) // Updates to 10 available

        val result1 = job1.await()

        assertEquals(1, result1)
        assertEquals(0, window.available)
    }

    @Test
    fun testMplexStreamReadWrite() = runTest {
        val sessionWindow = SessionWindow(100)
        var writtenData = byteArrayOf()
        var writtenId = -1L

        val stream = MplexStream(1, sessionWindow) { id, data ->
            writtenId = id
            writtenData = data
        }

        val dataToWrite = byteArrayOf(1, 2, 3)
        stream.write(dataToWrite)

        assertEquals(1L, writtenId)
        assertTrue(dataToWrite.contentEquals(writtenData))
        assertEquals(97, sessionWindow.available)
        assertEquals(65535 - 3, stream.streamWindow.available)

        stream.receiveData(byteArrayOf(4, 5, 6))
        val readData = stream.read(2)
        assertTrue(byteArrayOf(4, 5).contentEquals(readData))

        val readData2 = stream.read(10)
        assertTrue(byteArrayOf(6).contentEquals(readData2))
    }

    @Test
    fun testReadExactly() = runTest {
        val sessionWindow = SessionWindow(100)
        val stream = MplexStream(1, sessionWindow)

        launch {
            stream.receiveData(byteArrayOf(1))
            delay(10)
            stream.receiveData(byteArrayOf(2, 3))
        }

        val result = stream.readExactly(3)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(result))
    }
}
