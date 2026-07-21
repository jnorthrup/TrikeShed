package borg.trikeshed.btrfs

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking

class BtrfsVolumeTest {

    private fun writeULongLE(buf: ByteArray, offset: Int, value: ULong) {
        for (i in 0..7) {
            buf[offset + i] = ((value shr (i * 8)) and 0xFFuL).toByte()
        }
    }

    private fun writeUIntLE(buf: ByteArray, offset: Int, value: UInt) {
        for (i in 0..3) {
            buf[offset + i] = ((value shr (i * 8)) and 0xFFu).toByte()
        }
    }
    
    private fun writeUByte(buf: ByteArray, offset: Int, value: UByte) {
        buf[offset] = value.toByte()
    }

    private fun writeUShortLE(buf: ByteArray, offset: Int, value: UShort) {
        buf[offset] = (value.toUInt() and 0xFFu).toByte()
        buf[offset + 1] = ((value.toUInt() shr 8) and 0xFFu).toByte()
    }

    @Test
    fun openFixtureImageReadsSuperblock() { // verifies: parses superblock, sets capacity
        val f = File.createTempFile("btrfs_test_", ".img")
        f.deleteOnExit()
        
        val buf = ByteArray(4096 + 8192) // 4k super + 8k zeros
        writeULongLE(buf, 16, 0x4D5F53665248425F.toULong()) // BTRFS_MAGIC
        writeULongLE(buf, 48, (1024 * 1024).toULong()) // totalBytes = 1MiB
        
        f.writeBytes(buf)
        
        val vol = BtrfsVolume(f.absolutePath)
        assertEquals(4096, vol.blockSize)
        assertEquals(1024L * 1024L, vol.capacity)
    }

    @Test
    fun writeReturnsUnsupported() { // verifies: write throws UnsupportedOperationException
        val f = File.createTempFile("btrfs_test_", ".img")
        f.deleteOnExit()
        
        val buf = ByteArray(4096)
        writeULongLE(buf, 16, 0x4D5F53665248425F.toULong())
        f.writeBytes(buf)
        
        val vol = BtrfsVolume(f.absolutePath)
        val ex = assertFailsWith<UnsupportedOperationException> {
            runBlocking {
                vol.write(0L, ByteArray(4096))
            }
        }
        assertEquals("BtrfsVolume is read-only; COW not in scope for this task", ex.message)
    }

    @Test
    fun readAtKnownChunkReturnsExpectedBytes() { // verifies: reads chunk items via logical offset mapping
        val f = File.createTempFile("btrfs_test_", ".img")
        f.deleteOnExit()
        
        // 4096 super + 4096 tree node + 4096 data = 12288 bytes
        val buf = ByteArray(12288)
        
        // Superblock
        writeULongLE(buf, 16, 0x4D5F53665248425F.toULong()) // Magic
        writeULongLE(buf, 48, 12288uL) // totalBytes
        writeULongLE(buf, 40, 4096uL) // chunkRoot at 4096
        
        // Chunk Tree Node (at offset 4096)
        val treeOff = 4096
        writeUIntLE(buf, treeOff + 96, 1u) // nritems = 1
        
        // Item 0: key (objectid=0, type=228, offset=0)
        writeULongLE(buf, treeOff + 101, 0uL) // objectid
        writeUByte(buf, treeOff + 101 + 8, 228u) // type
        writeULongLE(buf, treeOff + 101 + 9, 0uL) // offset=0 (logical offset)
        
        // Data offsets are relative to the start of the node + 101 byte header
        writeUIntLE(buf, treeOff + 101 + 17, 200u) // data offset
        writeUIntLE(buf, treeOff + 101 + 21, 100u) // data size
        
        val p = treeOff + 101 + 200 // offset relative to the node start
        writeULongLE(buf, p, 4096uL) // chunk length
        writeUByte(buf, p + 24, 2u) // type (SINGLE)
        writeUShortLE(buf, p + 44, 1u) // numStripes = 1
        writeUShortLE(buf, p + 46, 1u) // subStripes = 1
        
        writeULongLE(buf, p + 48, 1uL) // devid
        writeULongLE(buf, p + 56, 8192uL) // physical offset = 8192
        
        // Write expected data at physical offset 8192
        for (i in 0 until 4096) {
            buf[8192 + i] = (i % 256).toByte()
        }
        
        f.writeBytes(buf)
        
        val vol = BtrfsVolume(f.absolutePath)
        
        val readBytes = runBlocking {
            vol.read(0L, 1) // logical block 0, count 1
        }
        
        assertEquals(4096, readBytes.size)
        for (i in 0 until 4096) {
            assertEquals((i % 256).toByte(), readBytes[i])
        }
    }
}
