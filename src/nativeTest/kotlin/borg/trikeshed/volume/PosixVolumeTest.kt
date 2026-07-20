package borg.trikeshed.volume

import borg.trikeshed.common.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.remove
import kotlin.test.*

class PosixVolumeTest {

    private fun tempFile(): String {
        return createTempDirectory("posixvoltest") + "/vol.dat"
    }

    @Test
    fun roundTrip100BlocksOf4096() = runBlocking {
        // verifies: create volume with capacity 4096 * 100, write 100 distinct blocks, sync(), reopen from same path, read each block N, assert pattern matches.
        val path = tempFile()
        try {
            var vol = PosixVolume(path, 4096, 4096L * 100)
            for (i in 0 until 100) {
                val buf = ByteArray(4096) { (i and 0xFF).toByte() }
                vol.write(i.toLong(), buf)
            }
            vol.sync()
            vol.close()

            vol = PosixVolume(path, 4096, 4096L * 100)
            for (i in 0 until 100) {
                val buf = vol.read(i.toLong(), 1)
                for (j in buf.indices) {
                    assertEquals((i and 0xFF).toByte(), buf[j])
                }
            }
            vol.close()
        } finally {
            remove(path)
        }
    }

    @Test
    fun partialBlockWriteFails() = runBlocking {
        // verifies: write a 2049-byte array when blockSize = 4096 -> expect IllegalArgumentException("write 2049 > blockSize 4096").
        val path = tempFile()
        try {
            val vol = PosixVolume(path, 4096, 4096L * 1)
            val ex = assertFailsWith<IllegalArgumentException> {
                vol.write(0L, ByteArray(2049))
            }
            assertEquals("write 2049 > blockSize 4096", ex.message)
            vol.close()
        } finally {
            remove(path)
        }
    }

    @Test
    fun misalignedCapacityFails() {
        // verifies: constructor with capacityBytes = 4097 -> expect IllegalArgumentException("capacity 4097 not aligned to blockSize 4096").
        val path = tempFile()
        try {
            val ex = assertFailsWith<IllegalArgumentException> {
                PosixVolume(path, 4096, 4097L)
            }
            assertEquals("capacity 4097 not aligned to blockSize 4096", ex.message)
        } finally {
            remove(path)
        }
    }

    @Test
    fun syncThenRereadAcrossFreshInstance() = runBlocking {
        // verifies: write 10 blocks, sync, drop the volume reference, open a new PosixVolume on the same path, read 10 blocks, assert content matches.
        val path = tempFile()
        try {
            var vol = PosixVolume(path, 4096, 4096L * 10)
            for (i in 0 until 10) {
                vol.write(i.toLong(), ByteArray(4096) { i.toByte() })
            }
            vol.sync()
            vol.close()

            vol = PosixVolume(path, 4096, 4096L * 10)
            for (i in 0 until 10) {
                val buf = vol.read(i.toLong(), 1)
                assertEquals(4096, buf.size)
                for (b in buf) {
                    assertEquals(i.toByte(), b)
                }
            }
            vol.close()
        } finally {
            remove(path)
        }
    }

    @Test
    fun concurrentWritersPreserveAllBytes() = runBlocking(Dispatchers.Default) {
        // verifies: launch 4 coroutines, each writes 25 non-overlapping blocks, join all, then read every block and assert content. (Confirms synchronized(this) works.)
        val path = tempFile()
        try {
            val vol = PosixVolume(path, 4096, 4096L * 100)
            val jobs = (0 until 4).map { w ->
                launch {
                    val start = w * 25
                    for (i in start until start + 25) {
                        vol.write(i.toLong(), ByteArray(4096) { i.toByte() })
                    }
                }
            }
            jobs.joinAll()
            vol.sync()

            for (i in 0 until 100) {
                val buf = vol.read(i.toLong(), 1)
                for (b in buf) {
                    assertEquals(i.toByte(), b)
                }
            }
            vol.close()
        } finally {
            remove(path)
        }
    }

    @Test
    fun readBeyondCapacityReturnsZeros() = runBlocking {
        // verifies: read at LBA = capacity/blockSize (one past the end) -> returns ByteArray(blockSize) of zeros (or the OS's read-past-end behaviour; assert .size == blockSize).
        val path = tempFile()
        try {
            val vol = PosixVolume(path, 4096, 4096L * 2)
            val buf = vol.read(2L, 1)
            assertEquals(4096, buf.size)
            for (b in buf) {
                assertEquals(0.toByte(), b)
            }
            vol.close()
        } finally {
            remove(path)
        }
    }

    @Test
    fun blockSizeIsExposed() = runBlocking {
        // verifies: assert volume.blockSize == 4096.
        val path = tempFile()
        try {
            val vol = PosixVolume(path, 4096, 4096L * 1)
            assertEquals(4096, vol.blockSize)
            vol.close()
        } finally {
            remove(path)
        }
    }
}
