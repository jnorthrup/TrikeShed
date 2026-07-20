package borg.trikeshed.browser.storage

import borg.trikeshed.userspace.volume.Volume
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeBlockDevice(override val blockSize: Int = 4096, val capacityBytes: Long = blockSize * 1024L) : BlockDevice {
    val memory = ByteArray(capacityBytes.toInt())
    var isClosed = false

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (isClosed) throw IllegalStateException("block device closed")
        val result = ByteArray(length)
        if (offset < memory.size) {
            val toCopy = minOf(length, memory.size - offset.toInt())
            memory.copyInto(result, 0, offset.toInt(), offset.toInt() + toCopy)
        }
        return result
    }

    override suspend fun write(offset: Long, data: ByteArray) {
        if (isClosed) throw IllegalStateException("block device closed")
        if (offset < memory.size) {
            val toCopy = minOf(data.size, memory.size - offset.toInt())
            data.copyInto(memory, offset.toInt(), 0, toCopy)
        }
    }

    override suspend fun sync() {}

    override suspend fun close() {
        isClosed = true
    }
}

class BrowserVolumeContractTest {

    // verifies: opfsVolumeRoundTrips100Blocks
    @Test
    fun opfsVolumeRoundTrips100Blocks() = runTest {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)
        val volume = OpfsVolume(device, config)

        for (i in 0 until 100) {
            val block = ByteArray(config.blockSize) { (i and 0xFF).toByte() }
            volume.write(i.toLong(), block)
        }
        volume.sync()

        for (i in 0 until 100) {
            val readBlock = volume.read(i.toLong(), 1)
            assertEquals(config.blockSize, readBlock.size)
            for (j in 0 until config.blockSize) {
                assertEquals((i and 0xFF).toByte(), readBlock[j])
            }
        }
    }

    // verifies: indexedDbVolumeRoundTrips100Blocks
    @Test
    fun indexedDbVolumeRoundTrips100Blocks() = runTest {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)
        val volume = IndexedDbVolume(device, config)

        for (i in 0 until 100) {
            val block = ByteArray(config.blockSize) { (i and 0xFF).toByte() }
            volume.write(i.toLong(), block)
        }
        volume.sync()

        for (i in 0 until 100) {
            val readBlock = volume.read(i.toLong(), 1)
            assertEquals(config.blockSize, readBlock.size)
            for (j in 0 until config.blockSize) {
                assertEquals((i and 0xFF).toByte(), readBlock[j])
            }
        }
    }

    // verifies: partialBlockWriteFails
    @Test
    fun partialBlockWriteFails() = runTest {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)

        val opfs = OpfsVolume(device, config)
        val idb = IndexedDbVolume(device, config)

        val oversizedData = ByteArray(config.blockSize + 1)

        val ex1 = assertFailsWith<IllegalArgumentException> {
            opfs.write(0L, oversizedData)
        }
        assertEquals("write ${oversizedData.size} > blockSize ${config.blockSize}", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> {
            idb.write(0L, oversizedData)
        }
        assertEquals("write ${oversizedData.size} > blockSize ${config.blockSize}", ex2.message)
    }

    // verifies: misalignedCapacityConfigFails
    @Test
    fun misalignedCapacityConfigFails() {
        assertFailsWith<IllegalArgumentException> {
            BrowserVolumeConfig(capacityBytes = 4097)
        }
    }

    // verifies: rejectsNegativeNamespace
    @Test
    fun rejectsNegativeNamespace() {
        assertFailsWith<IllegalArgumentException> {
            BrowserVolumeConfig(namespace = "hello:world")
        }
    }

    // verifies: readBeyondCapacityReturnsZeros
    @Test
    fun readBeyondCapacityReturnsZeros() = runTest {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)
        val volume = OpfsVolume(device, config)

        val readBlock = volume.read(config.capacityBytes / config.blockSize, 1)
        assertEquals(config.blockSize, readBlock.size)
        for (j in 0 until config.blockSize) {
            assertEquals(0.toByte(), readBlock[j])
        }
    }

    // verifies: volumeAndBlockDeviceShareTheSameBlockSize
    @Test
    fun volumeAndBlockDeviceShareTheSameBlockSize() {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)
        val opfs = OpfsVolume(device, config)

        assertEquals(opfs.blockSize, device.blockSize)
        assertEquals(opfs.blockSize, config.blockSize)
    }

    // verifies: concurrentWritesAreSerialized
    @Test
    fun concurrentWritesAreSerialized() = runTest {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)
        val volume = OpfsVolume(device, config)

        coroutineScope {
            for (writer in 0 until 4) {
                launch {
                    for (i in 0 until 25) {
                        val lba = writer * 25 + i
                        val block = ByteArray(config.blockSize) { (lba and 0xFF).toByte() }
                        volume.write(lba.toLong(), block)
                    }
                }
            }
        }
        volume.sync()

        for (i in 0 until 100) {
            val readBlock = volume.read(i.toLong(), 1)
            assertEquals(config.blockSize, readBlock.size)
            for (j in 0 until config.blockSize) {
                assertEquals((i and 0xFF).toByte(), readBlock[j])
            }
        }
    }

    // verifies: syncIsIdempotent
    @Test
    fun syncIsIdempotent() = runTest {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)
        val volume = OpfsVolume(device, config)

        volume.sync()
        volume.sync()
    }

    // verifies: closePreventsFurtherReads
    @Test
    fun closePreventsFurtherReads() = runTest {
        val config = BrowserVolumeConfig(flushDebounceMs = 0)
        val device = FakeBlockDevice(config.blockSize, config.capacityBytes)
        val volume = OpfsVolume(device, config)

        volume.close()

        val ex = assertFailsWith<IllegalStateException> {
            volume.read(0L, 1)
        }
        assertEquals("volume closed", ex.message)
    }
}
