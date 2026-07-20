package borg.trikeshed.cas

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.*

class MmapCasStoreJvmTest {

    @Test
    fun mmapRoundTrip(@TempDir tempDir: File) = runBlocking {
        val file = File(tempDir, "store.db")
        val store = MmapCasStoreJvm(file)

        val data = Random.nextBytes(64 * 1024) // 64 KiB
        val cid = store.put(data)

        val retrieved = store.get(cid)
        assertNotNull(retrieved)
        assertTrue(data.contentEquals(retrieved))
    }

    @Test
    fun mmapSyncAcrossInstances(@TempDir tempDir: File) = runBlocking {
        val file = File(tempDir, "store.db")
        val store1 = MmapCasStoreJvm(file)

        val data = Random.nextBytes(1024)
        val cid = store1.put(data)
        store1.sync()
        // Wait just in case, but MmapCasStoreJvm.sync() should write to file synchronously

        val store2 = MmapCasStoreJvm(file)
        val retrieved = store2.get(cid)

        assertNotNull(retrieved)
        assertTrue(data.contentEquals(retrieved))
    }

    @Test
    fun mmapConcurrentPuts(@TempDir tempDir: File) = runBlocking {
        val file = File(tempDir, "store.db")
        val store = MmapCasStoreJvm(file)

        val dataList = (0 until 8).map { Random.nextBytes(1024) }

        val cids = dataList.map { data ->
            async(Dispatchers.IO) {
                store.put(data)
            }
        }.awaitAll()

        for (i in 0 until 8) {
            val retrieved = store.get(cids[i])
            assertNotNull(retrieved)
            assertTrue(dataList[i].contentEquals(retrieved))
        }
    }
}
