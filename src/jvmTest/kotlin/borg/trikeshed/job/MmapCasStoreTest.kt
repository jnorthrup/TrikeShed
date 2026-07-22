package borg.trikeshed.job

import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.ConfixIndexK
import borg.trikeshed.parse.confix.facet
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

        // Read via get
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
        assertEquals(data1.size, retrieved1.a)
        for (i in data1.indices) {
            assertEquals(data1[i], retrieved1[i])
        }

        val retrieved2 = store2.get(cid2)
        assertNotNull(retrieved2)
        assertEquals(data2.size, retrieved2.a)
        for (i in data2.indices) {
            assertEquals(data2[i], retrieved2[i])
        }

        store2.close()
    }

    @Test
    fun testMmapConfixIndexOverMappedBytes() {
        val tempFile = Files.createTempFile("mmap-cas-store-confix", ".dat")
        tempFile.toFile().deleteOnExit()
        val store = MmapCasStore(tempFile)

        val jsonStr = """{"hello": "world", "data": [1, 2, 3]}"""
        val cid = store.put(jsonStr.encodeToByteArray())

        val mappedSeries = store.get(cid)
        assertNotNull(mappedSeries)

        // Composes: mmap file -> Series<Byte> -> Confix index over mapped bytes without copy
        val confixIndex = Syntax.JSON.scanIndex(mappedSeries)
        val tags = confixIndex.facet(ConfixIndexK.Tags)
        
        assertTrue(tags.a > 0)
        
        store.close()
    }
}
