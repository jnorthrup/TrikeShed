cat << 'INNER_EOF' > src/jvmMain/kotlin/borg/trikeshed/job/MmapCasStore.kt
package borg.trikeshed.job

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import java.io.RandomAccessFile
import borg.trikeshed.collections.associative.LinearHashMap

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class MmapCasStore(val file: Path) : CasStore() {
    private val randomAccessFile = RandomAccessFile(file.toFile(), "rw")
    private val channel = randomAccessFile.channel

    // We parse the file contents on initialization to rebuild the index.
    // Format per blob: [4 bytes length][N bytes content]

    // Use LinearHashMap for non-mmap fallback/index as requested
    private val offsetMap = LinearHashMap<ContentId, Pair<Long, Int>>()
    private var writeOffset: Long = 0L

    // Keep a single mapped region to avoid memory leaks. We remap when file grows.
    private var currentArena: Arena? = null
    private var currentMapping: MemorySegment? = null
    private var mappedSize: Long = 0L
    private val mapLock = Any()

    init {
        rebuildIndex()
    }

    @Synchronized
    private fun rebuildIndex() {
        writeOffset = 0L
        val size = channel.size()
        if (size == 0L) return
        val header = ByteBuffer.allocate(4)

        while (writeOffset + 4 <= size) {
            header.clear()
            val read = channel.read(header, writeOffset)
            if (read < 4) break

            header.flip()
            val len = header.getInt()

            if (len <= 0 || writeOffset + 4 + len > size) {
                // corrupted or partial write, truncate
                channel.truncate(writeOffset)
                break
            }

            // read content to compute CID
            val buf = ByteBuffer.allocate(len)
            channel.read(buf, writeOffset + 4)
            val bytes = buf.array()
            val cid = ContentId.of(bytes)

            offsetMap[cid] = Pair(writeOffset + 4, len)
            writeOffset += 4 + len
        }

        remap()
    }

    private fun remap() {
        synchronized(mapLock) {
            val size = channel.size()
            if (size == 0L) return
            if (size > 0 && size > mappedSize) {
                currentArena?.close()
                val newArena = Arena.ofShared()
                currentMapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, newArena)
                currentArena = newArena
                mappedSize = size
            }
        }
    }

    override fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        synchronized(this) {
            if (cid in offsetMap) return cid

            val len = bytes.size
            val buffer = ByteBuffer.allocate(4 + len)
            buffer.putInt(len)
            buffer.put(bytes)
            buffer.flip()

            channel.write(buffer, writeOffset)

            offsetMap[cid] = Pair(writeOffset + 4, len)
            writeOffset += 4 + len
        }
        remap() // Remap after write to include new data
        return cid
    }

    override fun get(cid: ContentId): ByteArray? {
        val loc = synchronized(this) { offsetMap[cid] } ?: return null
        val offset = loc.first
        val length = loc.second
        val buffer = ByteBuffer.allocate(length)
        channel.read(buffer, offset)
        return buffer.array()
    }

    fun getMapped(cid: ContentId): Series<Byte>? {
        val loc = synchronized(this) { offsetMap[cid] } ?: return null
        val offset = loc.first
        val length = loc.second
        val map = synchronized(mapLock) { currentMapping } ?: return null

        // Return a series that reads from the mapped buffer without copy
        return length j { i: Int -> map.get(ValueLayout.JAVA_BYTE, offset + i.toLong()) }
    }

    fun close() {
        synchronized(mapLock) {
            currentArena?.close()
            currentArena = null
            currentMapping = null
        }
        channel.close()
        randomAccessFile.close()
    }
}
INNER_EOF

cat << 'INNER_EOF' > src/jvmTest/kotlin/borg/trikeshed/job/MmapCasStoreTest.kt
package borg.trikeshed.job

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import java.nio.file.Files
import borg.trikeshed.lib.get

class MmapCasStoreTest {
    @Test
    fun testMmapCasStoreZeroHeapAllocation() {
        val tempFile = Files.createTempFile("mmap-cas-store", ".dat")
        tempFile.toFile().deleteOnExit()

        val store = MmapCasStore(tempFile)

        // 1MB blob
        val size = 1024 * 1024
        val bytes = ByteArray(size) { (it % 256).toByte() }
        val cid = store.put(bytes)

        // Read via getMapped
        val mappedSeries = store.getMapped(cid)
        assertNotNull(mappedSeries)
        assertEquals(size, mappedSeries.a) // size

        // Verify content without copying to array
        assertEquals(0, mappedSeries[0].toInt())
        assertEquals(1, mappedSeries[1].toInt())
        assertEquals(255.toByte(), mappedSeries[255])
        assertEquals(0, mappedSeries[256].toInt())

        store.close()
    }

    @Test
    fun testRebuildIndex() {
        val tempFile = Files.createTempFile("mmap-cas-store-rebuild", ".dat")
        tempFile.toFile().deleteOnExit()

        val store = MmapCasStore(tempFile)
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(4, 5, 6, 7)
        val cid1 = store.put(data1)
        val cid2 = store.put(data2)
        store.close()

        val store2 = MmapCasStore(tempFile)
        val retrieved1 = store2.get(cid1)
        assertNotNull(retrieved1)
        assertTrue(data1.contentEquals(retrieved1))

        val retrieved2 = store2.get(cid2)
        assertNotNull(retrieved2)
        assertTrue(data2.contentEquals(retrieved2))

        store2.close()
    }
}
INNER_EOF
