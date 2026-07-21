/*
 * Copyright (c) 2017 TrikeShed Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package borg.trikeshed.userspace.volume

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import borg.trikeshed.lib.Closeable
import platform.posix.remove

class NativeVolumeTest {
    @Test
    fun testPosixVolumeEmpty() = runBlocking {
        val path = "test_posix_empty.bin"
        platform.posix.remove(path)
        val volume = PosixVolume(path, blockSize = 512, capacityBytes = 1024L)
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
        val volume = LiburingVolume(path, blockSize = 512, capacityBytes = 1024L)
        volume.write(0, ByteArray(0))
        val emptyRead = volume.read(0, 0)
        assertEquals(0, emptyRead.size)
        volume.close()
        platform.posix.remove(path)
    }

    @Test
    fun testPosixVolume() = runBlocking {
        val path = "test_posix_volume.bin"
        platform.posix.remove(path)
        val volume = PosixVolume(path, blockSize = 512, capacityBytes = 1024L)

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
        platform.posix.remove(path)
        val volume = LiburingVolume(path, blockSize = 512, capacityBytes = 1024L)

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

    @Test
    fun testPosixVolumeOutOfBounds() = runBlocking {
        val path = "test_posix_oob.bin"
        platform.posix.remove(path)
        val volume = PosixVolume(path, blockSize = 512, capacityBytes = 1024L)
        
        assertFailsWith<VolumeException> {
             volume.write(2, ByteArray(512))
        }
        assertFailsWith<VolumeException> {
             volume.read(2, 1)
        }

        volume.close()
        platform.posix.remove(path)
    }
    
    @Test
    fun testLiburingVolumeOutOfBounds() = runBlocking {
        val path = "test_liburing_oob.bin"
        platform.posix.remove(path)
        val volume = LiburingVolume(path, blockSize = 512, capacityBytes = 1024L)
        
        assertFailsWith<VolumeException> {
             volume.write(2, ByteArray(512))
        }
        assertFailsWith<VolumeException> {
             volume.read(2, 1)
        }

        volume.close()
        platform.posix.remove(path)
    }

    @Test
    fun testPosixConcurrency() = runBlocking {
        val path = "test_posix_concurrency.bin"
        platform.posix.remove(path)
        val volume = PosixVolume(path, blockSize = 512, capacityBytes = 4096L)

        val testData = ByteArray(512) { it.toByte() }
        volume.write(0, testData)
        volume.sync()

        // 4 concurrent readers
        val readers = (1..4).map {
            launch {
                val readData = volume.read(0, 1)
                assertEquals(512, readData.size)
                for (i in 0 until 512) {
                    assertEquals(testData[i], readData[i])
                }
            }
        }
        
        // 1 concurrent writer
        val writer = launch {
            volume.write(1, ByteArray(512) { (it + 1).toByte() })
            volume.sync()
        }

        readers.forEach { it.join() }
        writer.join()
        
        val newRead = volume.read(1, 1)
        assertEquals(1, newRead[0].toInt())

        volume.close()
        platform.posix.remove(path)
    }
    
    @Test
    fun testLiburingConcurrency() = runBlocking {
        val path = "test_liburing_concurrency.bin"
        platform.posix.remove(path)
        val volume = LiburingVolume(path, blockSize = 512, capacityBytes = 4096L)

        val testData = ByteArray(512) { it.toByte() }
        volume.write(0, testData)
        volume.sync()

        // 4 concurrent readers
        val readers = (1..4).map {
            launch {
                val readData = volume.read(0, 1)
                assertEquals(512, readData.size)
                for (i in 0 until 512) {
                    assertEquals(testData[i], readData[i])
                }
            }
        }
        
        // 1 concurrent writer
        val writer = launch {
            volume.write(1, ByteArray(512) { (it + 1).toByte() })
            volume.sync()
        }

        readers.forEach { it.join() }
        writer.join()
        
        val newRead = volume.read(1, 1)
        assertEquals(1, newRead[0].toInt())

        volume.close()
        platform.posix.remove(path)
    }

    @Test
    fun testCcekLifecycle() = runBlocking {
        val path = "test_lifecycle.bin"
        platform.posix.remove(path)
        
        var volume = PosixVolume(path, blockSize = 512, capacityBytes = 1024L)
        val testData = ByteArray(512) { 42.toByte() }
        volume.write(0, testData)
        volume.sync()
        volume.close()
        
        assertFailsWith<IllegalStateException> {
            volume.read(0, 1)
        }
        
        // Reopen
        volume = PosixVolume(path, blockSize = 512, capacityBytes = 1024L)
        val readData = volume.read(0, 1)
        assertEquals(42, readData[0].toInt())
        volume.close()
        
        platform.posix.remove(path)
    }
}
