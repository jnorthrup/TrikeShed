package borg.trikeshed.userspace.nio

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeTest {

    @Test
    fun testInMemoryVolume() = runTest {
        val volume = InMemoryVolume(blockSize = 512, capacity = 10)
        assertEquals(512, volume.blockSize)
        assertEquals(10L, volume.capacity)

        val writeData = ByteBuffer.allocate(512)
        writeData.putInt(42)
        writeData.position(0)

        volume.write(2, writeData)

        val readData = volume.read(2, 1)
        assertEquals(42, readData.getInt())
    }

    @Test
    fun testBlockArray() = runTest {
        val volume = InMemoryVolume(blockSize = 512, capacity = 10)
        val array = BlockArray(volume)

        val writeData = ByteBuffer.allocate(512)
        writeData.putInt(100)
        writeData.position(0)

        array.set(3, writeData)

        val readData = array.get(3)
        assertEquals(100, readData.getInt())
    }

    @Test
    fun testBootBlock() = runTest {
        val volume = InMemoryVolume(blockSize = 512, capacity = 10)
        val boot = BootBlock(volume)

        val writeData = ByteBuffer.allocate(512)
        writeData.putLong(0xDEADBEEFL)
        writeData.position(0)

        boot.write(writeData)

        val readData = boot.read()
        assertEquals(0xDEADBEEFL, readData.getLong())
    }
}
