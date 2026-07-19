package borg.trikeshed.userspace.volume

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class NativeVolumeTest {
    @Test
    fun testPosixVolume() = runBlocking {
        val path = "test_posix_volume.bin"
        val volume = PosixVolume(path, blockSize = 512)

        val testData = ByteArray(512) { it.toByte() }
        volume.write(0, testData)
        volume.sync()

        val readData = volume.read(0, 1)
        assertEquals(512, readData.size)
        for (i in 0 until 512) {
            assertEquals(testData[i], readData[i])
        }

        platform.posix.remove(path)
    }
}
