package borg.trikeshed.ipfs

import kotlin.test.*
import java.io.File
import kotlinx.coroutines.runBlocking

class DiskBlockStoreTest {
    @Test
    fun putGet() = runBlocking {
        val tmp = File("build/tmp/ipfs-test")
        tmp.deleteRecursively()
        val store = DiskBlockStore(tmp)
        val cid = CID(byteArrayOf(1,2,3))
        val data = "hello".toByteArray()
        store.put(cid, data)
        val got = store.get(cid)
        assertNotNull(got)
        assertEquals(String(data), String(got!!))
    }
}
