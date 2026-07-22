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
        val mappedSeries = store.get(cid)
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
        val expected1 = data1.toList()
        val actual1 = List(retrieved1.a) { retrieved1[it] }
        assertEquals(expected1, actual1)

        val retrieved2 = store2.get(cid2)
        assertNotNull(retrieved2)
        val expected2 = data2.toList()
        val actual2 = List(retrieved2.a) { retrieved2[it] }
        assertEquals(expected2, actual2)

        store2.close()
    }
}
