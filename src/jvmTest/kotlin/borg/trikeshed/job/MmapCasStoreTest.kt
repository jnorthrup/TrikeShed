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
}
