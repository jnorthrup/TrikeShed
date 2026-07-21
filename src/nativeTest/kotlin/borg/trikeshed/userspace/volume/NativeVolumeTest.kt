package borg.trikeshed.userspace.volume

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class NativeVolumeTest {
    @Test
    fun testPosixVolumeEmpty() = runBlocking {
        val path = "test_posix_empty.bin"
        platform.posix.remove(path)
        val volume = PosixVolume(path, blockSize = 512)
        volume.write(0, ByteArray(0))
        val emptyRead = volume.read(0, 0)
        assertEquals(0, emptyRead.size)
        volume.close()
        platform.posix.remove(path)
    }

    @Test
    fun testLiburingVolumeEmpty() = runBlocking {
        val path = "test_liburing_empty.bin"
        platform.posix.remove(path)
        val volume = LiburingVolume(path, blockSize = 512)
        volume.write(0, ByteArray(0))
        val emptyRead = volume.read(0, 0)
        assertEquals(0, emptyRead.size)
        volume.close()
        platform.posix.remove(path)
    }

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

        volume.close()
        platform.posix.remove(path)
    }

    @Test
    fun testLiburingVolume() = runBlocking {
        val path = "test_liburing_volume.bin"
        val volume = LiburingVolume(path, blockSize = 512)

        val testData = ByteArray(512) { (it + 1).toByte() }
        volume.write(0, testData)
        volume.sync()

        val readData = volume.read(0, 1)
        assertEquals(512, readData.size)
        for (i in 0 until 512) {
            assertEquals(testData[i], readData[i])
        }

        volume.close()
        platform.posix.remove(path)
    }
}